package com.screenclicker.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Handler
import android.view.Display
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.screenclicker.capture.CaptureResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * The privileged half of the app: this one component can both read the screen
 * ([takeScreenshot], API 30+) and inject touches ([dispatchGesture]). Everything else in
 * the app reaches the screen through it.
 *
 * Lifecycle: Android binds this when the user enables the service in accessibility
 * settings and unbinds it when they disable it or the system revokes it. There is at
 * most one instance; [instance] mirrors that so UI/engine code can reach the live
 * service without plumbing it through everywhere. If [instance] is null the service is
 * off and every operation degrades to a failure result.
 */
class ClickerAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ClickerAccessibilitySvc"

        /**
         * The system rejects screenshots taken more often than roughly once per second
         * (ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT). We wait out the window ourselves
         * rather than burn failed requests; a little margin over 1s for safety.
         */
        private const val MIN_CAPTURE_INTERVAL_MS = 1_100L

        /** How long the synthetic finger stays down. Short enough to feel instant. */
        private const val TAP_DURATION_MS = 60L

        @Volatile
        private var instance: ClickerAccessibilityService? = null

        val isRunning: Boolean get() = instance != null

        /** Package of the app currently in the foreground, when we can see it. */
        val foregroundPackage: String?
            get() = instance?.rootInActiveWindow?.packageName?.toString()

        /** Captures the current screen; fails gracefully when the service is off. */
        suspend fun captureScreen(): CaptureResult =
            instance?.capture()
                ?: CaptureResult.Failure("Accessibility service is not enabled")

        /** Taps the screen at the given coordinates; returns false when it did not land. */
        suspend fun tap(x: Float, y: Float): Boolean =
            instance?.tap(x, y) ?: false
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Serializes screenshot requests; see MIN_CAPTURE_INTERVAL_MS. */
    private val captureMutex = Mutex()
    private var lastCaptureRequestAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Accessibility service connected")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        Log.i(TAG, "Accessibility service unbound")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    // Not used for detection; the scan loop drives itself on a timer. We only listen to
    // window-state events so the system keeps the connection healthy and cheap.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    /**
     * One screenshot, throttled to the system rate limit. Returns [CaptureResult.Success]
     * with ARGB ints, or [CaptureResult.Failure] with a human-readable reason.
     *
     * Runs on the main thread (the service's callbacks arrive there anyway); the mutex
     * means concurrent callers queue up behind one rate-limit window instead of racing.
     */
    suspend fun capture(): CaptureResult = captureMutex.withLock {
        withContext(Dispatchers.Main) {
            val since = SystemClock.uptimeMillis() - lastCaptureRequestAt
            if (since in 1 until MIN_CAPTURE_INTERVAL_MS) {
                delay(MIN_CAPTURE_INTERVAL_MS - since)
            }
            lastCaptureRequestAt = SystemClock.uptimeMillis()
            suspendCancellableCoroutine { cont ->
                takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    mainHandler::post,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(result: ScreenshotResult) {
                            if (cont.isActive) {
                                cont.resume(convert(result))
                            } else {
                                result.hardwareBuffer.close()
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            if (cont.isActive) {
                                cont.resume(CaptureResult.Failure("takeScreenshot failed ($errorCode)"))
                            }
                        }
                    },
                )
            }
        }
    }

    /** Hardware-buffer to ARGB conversion; closes the buffer in every path. */
    private fun convert(result: ScreenshotResult): CaptureResult {
        val buffer = result.hardwareBuffer
        try {
            val hardware = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                ?: return CaptureResult.Failure("wrapHardwareBuffer returned null")
            // Hardware bitmaps live in GPU memory and cannot be read directly; copy()
            // produces the software bitmap we can pull pixels out of.
            val software = hardware.copy(Bitmap.Config.ARGB_8888, false)
                ?: return CaptureResult.Failure("Could not copy screenshot to software bitmap")
            val width = software.width
            val height = software.height
            val argb = IntArray(width * height)
            software.getPixels(argb, 0, width, 0, 0, width, height)
            return CaptureResult.Success(argb, width, height)
        } finally {
            buffer.close()
        }
    }

    /**
     * Injects a tap at (x, y) in screen pixels. Returns false if the system refused or
     * cancelled the gesture.
     */
    suspend fun tap(x: Float, y: Float): Boolean = withContext(Dispatchers.Main) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, TAP_DURATION_MS)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        suspendCancellableCoroutine { cont ->
            val dispatched = dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(false)
                    }
                },
                null,
            )
            if (!dispatched && cont.isActive) cont.resume(false)
        }
    }
}

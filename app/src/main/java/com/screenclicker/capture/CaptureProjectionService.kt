package com.screenclicker.capture

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.view.Display
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import com.screenclicker.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference

/**
 * The fast capture backend: a mediaProjection foreground service mirroring the display
 * into an [ImageReader].
 *
 * Android 14+ requires the foreground service (type mediaProjection) to be running
 * BEFORE MediaProjectionManager.getMediaProjection() is called, so the flow is: user
 * consents in MainActivity -> [start] carries the consent result here ->
 * startForeground -> getMediaProjection -> VirtualDisplay. One consent prompt per
 * capture session; the projection ends when the system revokes it or the service is
 * stopped (notification Stop action, or Settings -> Stop capture).
 *
 * Frame handling: the ImageReader listener converts the newest frame into an ARGB
 * IntArray under a lock and closes the Image immediately (the reader's buffer pool is
 * tiny and stalls if images are held). capture() returns the newest converted frame —
 * importantly, a STATIC screen produces no frame callbacks at all, in which case the
 * cached frame is exactly the right answer and is returned without waiting.
 */
class CaptureProjectionService : Service() {

    private class Frame(val argb: IntArray, val width: Int, val height: Int, val at: Long)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val latestFrame = AtomicReference<Frame?>(null)
    private val frameLock = Any()
    private val frameSignal = Channel<Unit>(Channel.CONFLATED)

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var displayListener: DisplayManager.DisplayListener? = null

    /** Size the mirror is currently running at, in pixels. */
    @Volatile
    private var mirrorWidth = 0

    @Volatile
    private var mirrorHeight = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        startInForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val data = intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_DATA, Intent::class.java) }
        if (resultCode == Int.MIN_VALUE || data == null) {
            Log.w(TAG, "missing consent result; stopping")
            stopSelf()
            return START_NOT_STICKY
        }
        if (projection != null) {
            Log.i(TAG, "projection already running")
            return START_NOT_STICKY
        }
        setUpProjection(resultCode, data)
        return START_NOT_STICKY
    }

    private fun setUpProjection(resultCode: Int, data: Intent) {
        val manager = getSystemService(MediaProjectionManager::class.java)
        val mediaProjection = manager.getMediaProjection(resultCode, data) ?: run {
            Log.w(TAG, "getMediaProjection returned null")
            stopSelf()
            return
        }
        projection = mediaProjection
        mediaProjection.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.i(TAG, "projection stopped by system/user")
                    stopSelf()
                }
            },
            null,
        )

        val bounds = getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        replaceMirror(bounds.width(), bounds.height())

        // A fold, an unfold or a display-size change leaves the mirror at the old size, and
        // from then on every frame is a differently scaled screen. Recreate it on the change.
        val displayManager = getSystemService(DisplayManager::class.java)
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit

            override fun onDisplayRemoved(displayId: Int) = Unit

            override fun onDisplayChanged(displayId: Int) {
                if (displayId != Display.DEFAULT_DISPLAY) return
                val current =
                    getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
                if (current.width() == mirrorWidth && current.height() == mirrorHeight) return
                Log.i(
                    TAG,
                    "display is now ${current.width()}x${current.height()}; recreating the mirror",
                )
                replaceMirror(current.width(), current.height())
            }
        }
        displayManager.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        displayListener = listener
    }

    /**
     * (Re)creates the reader and the virtual display at one size; safe to call on a resize.
     *
     * currentWindowMetrics, not maximumWindowMetrics: on a foldable the maximum describes the
     * unfolded display, and a mirror created at that size renders the current screen scaled —
     * after which no template cropped from an accessibility screenshot can ever match.
     */
    private fun replaceMirror(width: Int, height: Int) {
        val mediaProjection = projection ?: return
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mirrorWidth = width
        mirrorHeight = height
        // The cached frame and any pending wake-up belong to the previous size: without
        // draining the signal, a capture that starts right after a swap would wake on the
        // stale one and report "no frames" for a cycle.
        latestFrame.set(null)
        while (frameSignal.tryReceive().isSuccess) {
            // discard
        }

        val dpi = resources.displayMetrics.densityDpi
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 4)
        reader.setOnImageAvailableListener({ activeReader ->
            val image: Image = activeReader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                convert(image)
            } finally {
                image.close()
            }
            frameSignal.trySend(Unit)
        }, Handler(Looper.getMainLooper()))

        imageReader = reader
        virtualDisplay = runCatching {
            mediaProjection.createVirtualDisplay(
                "screen-clicker",
                width,
                height,
                dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                Handler(Looper.getMainLooper()),
            )
        }.getOrNull()
        if (virtualDisplay == null) {
            // Leave the service alive: a later display change can try again, and the runner
            // reports the capture failure clearly instead of matching stale pixels.
            Log.w(TAG, "could not create the mirror at ${width}x$height")
            reader.close()
            imageReader = null
            latestFrame.set(null)
        } else {
            Log.i(TAG, "projection running at ${width}x$height")
        }
    }

    /** Decomposes the RGBA image into ARGB ints, honoring row stride padding. */
    private fun convert(image: Image) {
        val width = image.width
        val height = image.height
        val argb = IntArray(width * height)
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        synchronized(frameLock) {
            for (y in 0 until height) {
                val rowBase = y * rowStride
                val outBase = y * width
                for (x in 0 until width) {
                    val i = rowBase + x * pixelStride
                    val r = buffer.get(i).toInt() and 0xFF
                    val g = buffer.get(i + 1).toInt() and 0xFF
                    val b = buffer.get(i + 2).toInt() and 0xFF
                    argb[outBase + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
            latestFrame.set(Frame(argb, width, height, SystemClock.uptimeMillis()))
        }
    }

    suspend fun capture(): CaptureResult {
        if (projection == null) {
            return CaptureResult.Failure("Screen capture permission is not granted")
        }
        // Fast path: a converted frame already exists (newest wins). Only when nothing
        // has ever arrived do we wait for the listener to deliver the first frame.
        if (latestFrame.get() == null) {
            withTimeoutOrNull(1_000) { frameSignal.receive() }
                ?: return CaptureResult.Failure("No frames from screen capture")
        }
        val frame = latestFrame.get()
            ?: return CaptureResult.Failure("No frames from screen capture")
        synchronized(frameLock) {
            // Re-read inside the lock: the mirror can be swapped (fold/unfold) between the wait
            // above and here, and a frame from the previous display size must not be handed out
            // as the current capture.
            val current = latestFrame.get()
                ?: return CaptureResult.Failure("No frames from screen capture")
            if (mirrorWidth > 0 &&
                (current.width != mirrorWidth || current.height != mirrorHeight)
            ) {
                return CaptureResult.Failure("Frame is from a previous display size")
            }
            return CaptureResult.Success(current.argb.copyOf(), current.width, current.height)
        }
    }

    private fun startInForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_capture),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = getString(R.string.channel_capture_description) },
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, CaptureProjectionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.capture_notification_text))
            .setOngoing(true)
            .addAction(0, getString(R.string.capture_stop), stopIntent)
            .build()
        // FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION only exists on API 34+; on 30-33
        // startForeground() without a type is correct and the service still works.
        if (Build.VERSION.SDK_INT >= 34) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        displayListener?.let {
            runCatching { getSystemService(DisplayManager::class.java).unregisterDisplayListener(it) }
        }
        displayListener = null
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        projection?.stop()
        projection = null
        latestFrame.set(null)
        scope.cancel()
        instance = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "CaptureProjection"
        private const val CHANNEL_ID = "capture"
        private const val NOTIFICATION_ID = 43
        private const val EXTRA_RESULT_CODE = "resultCode"
        private const val EXTRA_DATA = "data"
        const val ACTION_STOP = "com.screenclicker.action.STOP_CAPTURE"

        @Volatile
        private var instance: CaptureProjectionService? = null

        /** True once the user has granted a projection and it is still live. */
        val isReady: Boolean get() = instance?.projection != null

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, CaptureProjectionService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CaptureProjectionService::class.java))
        }

        suspend fun capture(): CaptureResult =
            instance?.capture()
                ?: CaptureResult.Failure("Screen capture permission is not granted")
    }
}

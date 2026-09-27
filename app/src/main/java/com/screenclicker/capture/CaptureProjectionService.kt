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

        val bounds = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        val width = bounds.width()
        val height = bounds.height()
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

        val display = mediaProjection.createVirtualDisplay(
            "screen-clicker",
            width,
            height,
            dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            Handler(Looper.getMainLooper()),
        )
        imageReader = reader
        virtualDisplay = display
        Log.i(TAG, "projection running at ${width}x${height}")
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
            return CaptureResult.Success(frame.argb.copyOf(), frame.width, frame.height)
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

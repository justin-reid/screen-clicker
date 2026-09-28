package com.screenclicker.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Path
import android.view.WindowManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import androidx.core.app.NotificationCompat
import com.screenclicker.R
import com.screenclicker.capture.Capturers
import com.screenclicker.capture.CaptureConsentActivity
import com.screenclicker.capture.CaptureProjectionService
import com.screenclicker.capture.CaptureResult
import com.screenclicker.engine.ScriptRunner
import com.screenclicker.model.GlobalSettings
import com.screenclicker.model.Script
import com.screenclicker.overlay.ConfigOverlayService
import com.screenclicker.overlay.ControlPanelService
import com.screenclicker.overlay.InstallerWindow
import com.screenclicker.store.ScriptStore
import com.screenclicker.store.SettingsRepo
import com.screenclicker.vision.GrayImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * The privileged half of the app: this one component can both read the screen
 * ([takeScreenshot], API 30+) and inject touches ([dispatchGesture]). It also hosts the
 * [ScriptRunner] — the engine needs the accessibility connection alive, so this is the
 * natural home for it.
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

        private const val ENGINE_CHANNEL_ID = "engine"
        private const val ENGINE_NOTIFICATION_ID = 42
        const val ACTION_START = "com.screenclicker.action.START"
        const val ACTION_STOP = "com.screenclicker.action.STOP"
        const val EXTRA_SCRIPT_ID = "scriptId"

        @Volatile
        private var instance: ClickerAccessibilityService? = null

        /** Bound and connected: operations will actually work right now. */
        val isRunning: Boolean get() = instance != null

        /**
         * True when the user has the service switched on in system settings. Narrower
         * than [isRunning]: enabled-but-not-yet-bound is a real state (the system binds
         * a moment after enabling, and Android disables the toggle on reinstall/update),
         * and it deserves a different message than "it is off".
         */
        fun isEnabled(context: android.content.Context): Boolean {
            val manager = context.getSystemService(AccessibilityManager::class.java) ?: return false
            val expected = ComponentName(context, ClickerAccessibilityService::class.java)
            return manager
                .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { info ->
                    val serviceInfo = info.resolveInfo?.serviceInfo ?: return@any false
                    ComponentName(serviceInfo.packageName, serviceInfo.name) == expected
                }
        }

        /** Id of the script currently executing, or null. UI and QS tile observe this. */
        private val _runningScriptId = MutableStateFlow<String?>(null)
        val runningScriptId = _runningScriptId.asStateFlow()

        /**
         * Latest line from the runner (scan timings, detections, errors). The floating
         * control panel shows it, so the user gets feedback without opening the app.
         */
        private val _lastRunnerEvent = MutableStateFlow<String?>(null)
        val lastRunnerEvent = _lastRunnerEvent.asStateFlow()

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

        /**
         * Temporarily hides the detection highlight layer so it cannot appear inside a
         * screenshot taken by the rule editor. Must be called from the main thread.
         */
        fun setDetectionLayerVisible(visible: Boolean) {
            instance?.detectionView?.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        }

        /** Opens the system Recents — how the rule editor lets the user switch apps. */
        fun goRecents(): Boolean =
            instance?.performGlobalAction(GLOBAL_ACTION_RECENTS) ?: false

        /**
         * Starts running the script with [scriptId]. Returns false when the
         * accessibility service is not enabled (nothing can run without it).
         */
        fun startScript(context: android.content.Context, scriptId: String): Boolean {
            val service = instance ?: return false
            service.beginRunWithConsent(scriptId)
            return true
        }

        fun stopScript() {
            instance?.endRun(releaseCapture = true)
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Serializes screenshot requests; see MIN_CAPTURE_INTERVAL_MS. */
    private val captureMutex = Mutex()
    private var lastCaptureRequestAt = 0L

    private var runnerJob: Job? = null

    /**
     * Detection highlights overlay; present only while a script runs with it enabled.
     * Generations guard a restart race: the old run's finally must not tear down the
     * new run's window when a script is restarted while already running.
     */
    /**
     * Written on the main thread, read from the runner's background dispatcher — hence
     * @Volatile, which is what makes the update visible to the scan loop at all.
     */
    @Volatile
    private var detectionView: com.screenclicker.overlay.DetectionHighlightView? = null
    private var detectionGeneration = 0

    /**
     * True while the system's package installer is the window on screen. See
     * [setInstallerUp] for why that has to change anything.
     */
    @Volatile
    private var installerUp = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Accessibility service connected")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        endRun(releaseCapture = true)
        Log.i(TAG, "Accessibility service unbound")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        endRun(releaseCapture = true)
        hideDetectionOverlay(0, force = true)
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> intent.getStringExtra(EXTRA_SCRIPT_ID)?.let { beginRun(it) }
            ACTION_STOP -> endRun(releaseCapture = true)
        }
        return START_NOT_STICKY
    }

    // Not used for detection; the scan loop drives itself on a timer. Window-state events
    // are how the service notices *whose* window is in front, which is what lets our
    // overlays step out of the installer's way (see setInstallerUp).
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        setInstallerUp(InstallerWindow.isInstaller(e.packageName?.toString()))
    }

    override fun onInterrupt() = Unit

    /**
     * True while the system is asking the user to install or update something.
     *
     * Our overlay windows draw above a normal app's dialog, and the editor's rectangles and
     * the floating panel also take touches there — a rectangle can cover most of the screen,
     * so the installer's Update button can end up with every tap landing on our window
     * instead. The button then does nothing at all, with nothing on screen to explain it.
     * So while the installer is up: the panel and the editor hide *and* stop taking touches,
     * the detection layer hides, and taps are refused (a script that kept clicking could tap
     * the install dialog itself). All of it comes back when the installer goes away.
     */
    private fun setInstallerUp(up: Boolean) {
        if (installerUp == up) return
        installerUp = up
        Log.i(
            TAG,
            if (up) "installer on screen: overlays stand down, taps refused"
            else "installer gone: overlays and taps back",
        )
        _lastRunnerEvent.value = getString(
            if (up) R.string.installer_stand_down else R.string.installer_resumed,
        )
        ControlPanelService.setStandDown(up)
        ConfigOverlayService.setStandDown(up)
        setDetectionLayerVisible(!up)
    }

    /**
     * Starts the scan-and-click loop for one script. Any previous run is stopped first.
     * Templates are decoded once here; the loop then runs on the service scope so it
     * dies with the service.
     */
    /**
     * True when the run about to start would use the fast (MediaProjection) capture
     * while its session is not live — the case that deserves the system's consent
     * dialog at the moment Play is pressed, instead of silently degrading to the ~1
     * frame/s accessibility capture or failing once per frame. Rules whose templates
     * were cropped with the accessibility backend never want the session, whatever the
     * setting says, so they never trigger a prompt.
     */
    private fun wantsFastCapture(settings: GlobalSettings, script: Script): Boolean {
        val authored = script.authoredBackends()
        if (authored.size == 1) return authored[0] == Capturers.BACKEND_MEDIA_PROJECTION
        return settings.captureBackend != Capturers.BACKEND_ACCESSIBILITY
    }

    /**
     * True when the script's templates were cropped in the fast capture's space, which
     * the accessibility screenshot's cannot stand in for (different sizes — a
     * space-mismatch refusal every frame). A denial for such a script is a refusal to
     * run, not a silent downgrade.
     */
    private fun requiresFastSpace(script: Script): Boolean =
        script.authoredBackends() == listOf(Capturers.BACKEND_MEDIA_PROJECTION)

    private fun Script.authoredBackends(): List<String> = rules
        .filter { it.enabled && it.hasTemplate }
        .map { it.captureBackend }
        .filter { it.isNotEmpty() }
        .distinct()

    /**
     * Starts a run, asking for the screen-capture session first when this script will
     * need it and the session is not live — the first time we need it, from Play,
     * wherever it is pressed. Everything a Start does lives in [beginRun]; this
     * wrapper exists because the consent dialog takes an unknown number of seconds,
     * so the decision and the wait happen off the main thread.
     */
    private fun beginRunWithConsent(scriptId: String) {
        serviceScope.launch(Dispatchers.Default) {
            val context = this@ClickerAccessibilityService
            val settings = SettingsRepo(context).load()
            val script = ScriptStore(context).list().firstOrNull { it.id == scriptId }
            if (script != null && wantsFastCapture(settings, script) && !CaptureProjectionService.isReady) {
                _lastRunnerEvent.value = "Asking for screen capture…"
                withContext(Dispatchers.Main) { standDownForConsent(true) }
                val granted = try {
                    CaptureConsentActivity.request(context)
                } finally {
                    // A cancel (Stop pressed while the dialog is up) must still bring the
                    // overlays back; plain withContext throws on entry for a cancelled
                    // coroutine, which is how overlays used to get stuck off screen.
                    withContext(Dispatchers.Main + NonCancellable) { standDownForConsent(false) }
                }
                if (!granted && requiresFastSpace(script)) {
                    Log.w(TAG, "start aborted: fast capture denied, script was authored in its space")
                    _lastRunnerEvent.value =
                        "Fast capture denied — '${script.name}' was captured with it, so grant screen capture to run it"
                    return@launch
                }
                if (!granted) {
                    Log.i(TAG, "fast capture denied; running with the accessibility capture")
                    _lastRunnerEvent.value = "Fast capture denied — using the accessibility capture (~1 frame/s)"
                }
            }
            withContext(Dispatchers.Main) { beginRun(scriptId) }
        }
    }

    /**
     * Steps the app's overlay windows out of the way while the system's consent dialog
     * is up. That dialog is an app window, and our TYPE_APPLICATION_OVERLAY windows
     * draw above app windows and take touches there — the same way they once swallowed
     * the installer's Update button — so the panel and the editor hide and go
     * untouchable, exactly as they do while the installer is up.
     */
    private fun standDownForConsent(up: Boolean) {
        ControlPanelService.setConsentUp(up)
        ConfigOverlayService.setConsentUp(up)
        setDetectionLayerVisible(!up)
    }

    private fun beginRun(scriptId: String) {
        // The session is worth keeping across a restart: the run about to start wants the
        // same capture it just had, and re-granting is a system dialog.
        if (runnerJob?.isActive == true) endRun()
        val context = this
        // The highlight window must be added from the main thread; everything else the
        // run needs (script JSON, template PNGs) is loaded below, off it.
        val detectionGen =
            if (SettingsRepo(context).load().showDetections) showDetectionOverlay() else -1
        // The scan loop (screenshot copy, grayscale, matching) is CPU-bound and must not
        // run on the main thread: it used to, which froze the UI and our own overlays for
        // most of every scan interval. Only the overlay calls below hop back to main.
        runnerJob = serviceScope.launch(Dispatchers.Default) {
            val job = coroutineContext[Job]
            try {
                // Loading scripts and decoding template PNGs is disk + CPU work; doing it
                // here instead of before the launch keeps Start from hitching the UI.
                val store = ScriptStore(context)
                val script = store.list().firstOrNull { it.id == scriptId }
                if (script == null) {
                    Log.w(TAG, "startScript: script $scriptId not found")
                    return@launch
                }
                val templates = HashMap<String, GrayImage>()
                for (rule in script.rules) {
                    store.loadTemplate(rule)?.let { templates[rule.id] = it }
                }
                val settings = SettingsRepo(context).load()
                // Prefer the backend the templates were captured with: the capture space has to
                // match the authored one or no template can ever match (see Rule.frameWidth).
                // Only rules the runner will actually use: a disabled rule's backend must not
                // force the whole script onto a capture space its active rules were not
                // authored in.
                val authored = script.rules
                    .filter { it.enabled && it.hasTemplate }
                    .map { it.captureBackend }
                    .filter { it.isNotEmpty() }
                    .distinct()
                if (authored.size > 1) {
                    Log.w(TAG, "rules were captured with different backends ${authored}")
                }
                val capturer = authored.singleOrNull()?.let { Capturers.byName(it) }
                    ?: Capturers.pick(settings)
                val runner = ScriptRunner(SettingsRepo(context), capturer)
                SettingsRepo(context).setLastRunScriptId(scriptId)
                _runningScriptId.value = scriptId
                withContext(Dispatchers.Main) { showRunNotification(script.name) }

                runner.run(
                    script = script,
                    templates = templates,
                    foregroundPackage = { foregroundPackage },
                    onTap = { x, y -> tap(x.toFloat(), y.toFloat()) },
                    onDetections = { matches ->
                        // Generation-guarded: a run that was cancelled but is still finishing
                        // a scan must not paint its rectangles into a newer run's window.
                        if (detectionGen == detectionGeneration) detectionView?.update(matches)
                    },
                    onEvent = { message ->
                        Log.i(TAG, "runner: $message")
                        _lastRunnerEvent.value = message
                    },
                )
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    Log.e(TAG, "runner crashed", e)
                }
            } finally {
                // Only the run that is still current may clear the shared state: a
                // cancelled predecessor cancelling later would otherwise report "idle"
                // (and kill the notification) while the new run is clicking away.
                if (runnerJob === job) {
                    _runningScriptId.value = null
                    cancelRunNotification()
                }
                // Reached with the coroutine already cancelled whenever a Stop did the
                // cancelling, and a plain withContext throws on entry for a cancelled
                // coroutine — which is how the highlight rectangles used to stay painted on
                // screen until the app was force-stopped. NonCancellable runs the block
                // regardless; [endRun] takes the layer down on the spot as well, so this is
                // the belt to that pair of braces.
                withContext(Dispatchers.Main + NonCancellable) { hideDetectionOverlay(detectionGen) }
            }
        }
    }

    /** Adds the highlight window (or reuses it) and returns this run's generation. */
    private fun showDetectionOverlay(): Int {
        detectionGeneration += 1
        if (detectionView != null) return detectionGeneration
        val view = com.screenclicker.overlay.DetectionHighlightView(this)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        try {
            getSystemService(WindowManager::class.java)!!.addView(view, params)
            // A run started while the installer is up gets the layer hidden from the start,
            // not just muted once the installer leaves.
            view.visibility = if (installerUp) View.INVISIBLE else View.VISIBLE
            detectionView = view
        } catch (e: Exception) {
            Log.w(TAG, "detection overlay unavailable", e)
        }
        return detectionGeneration
    }

    private fun hideDetectionOverlay(generation: Int, force: Boolean = false) {
        if (!force && generation != detectionGeneration) return
        detectionView?.let { view ->
            runCatching { getSystemService(WindowManager::class.java)!!.removeView(view) }
        }
        detectionView = null
    }

    /**
     * Stops the running script and takes down what it left on screen.
     *
     * Called from the main thread by every Stop affordance (the floating panel, the app,
     * the QS tile, the run notification). [releaseCapture] additionally ends the
     * MediaProjection session: that session is what paints the system's "screen is being
     * shared" indicator and the capture notification, and it is granted per session — so a
     * stopped script used to keep sharing the screen (and keep showing match rectangles)
     * until the user force-stopped the app to get rid of it.
     *
     * The detection layer is removed here rather than only from the runner's own finally:
     * that finally belongs to the coroutine Stop just cancelled.
     */
    private fun endRun(releaseCapture: Boolean = false) {
        runnerJob?.cancel()
        runnerJob = null
        _runningScriptId.value = null
        cancelRunNotification()
        hideDetectionOverlay(0, force = true)
        if (releaseCapture) {
            CaptureProjectionService.stop(this)
            Log.i(TAG, "stop: script, overlays and capture session released")
        }
    }

    private fun showRunNotification(scriptName: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                ENGINE_CHANNEL_ID,
                "Script running",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Shown while a clicker script is running." },
        )
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, ClickerAccessibilityService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, ENGINE_CHANNEL_ID)
            .setSmallIcon(com.screenclicker.R.drawable.ic_notification)
            .setContentTitle(getString(com.screenclicker.R.string.app_name))
            .setContentText("Running: $scriptName")
            .setOngoing(true)
            .addAction(0, "Stop", stopIntent)
            .build()
        manager.notify(ENGINE_NOTIFICATION_ID, notification)
    }

    private fun cancelRunNotification() {
        getSystemService(NotificationManager::class.java).cancel(ENGINE_NOTIFICATION_ID)
    }

    /** A screenshot or the system's refusal; see [requestScreenshot]. */
    private class ScreenshotOutcome(val result: ScreenshotResult?, val errorCode: Int)

    /**
     * One screenshot, throttled to the system rate limit. Returns [CaptureResult.Success]
     * with ARGB ints, or [CaptureResult.Failure] with a human-readable reason.
     *
     * The *request* has to happen on the main thread (that is where the callback and the
     * rate-limit timer live) but the hardware-buffer → ARGB copy is ~2.8M pixels, so it
     * runs on a background dispatcher. Doing that copy on main stalled the UI on every
     * scan — with a 500ms scan interval that was the app's single worst source of lag.
     * The mutex means concurrent callers queue behind one rate-limit window.
     */
    suspend fun capture(): CaptureResult = captureMutex.withLock {
        val outcome = requestScreenshot()
        val shot = outcome.result
            ?: return@withLock CaptureResult.Failure("takeScreenshot failed (${outcome.errorCode})")
        withContext(Dispatchers.Default) { convert(shot) }
    }

    private suspend fun requestScreenshot(): ScreenshotOutcome = withContext(Dispatchers.Main) {
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
                            cont.resume(ScreenshotOutcome(result, 0))
                        } else {
                            result.hardwareBuffer.close()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        if (cont.isActive) cont.resume(ScreenshotOutcome(null, errorCode))
                    }
                },
            )
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
        // Never tap while the installer is on screen: the script's taps would land on the
        // install dialog, and a stray tap there can cancel the install. The runner treats a
        // refused gesture as "not now" (it does not burn the rule's interval), so the script
        // simply resumes when the installer goes away.
        if (installerUp) return@withContext false
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

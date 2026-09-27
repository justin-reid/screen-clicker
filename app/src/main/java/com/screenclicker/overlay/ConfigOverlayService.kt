package com.screenclicker.overlay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import kotlin.math.abs
import androidx.core.app.ServiceCompat
import com.screenclicker.R
import com.screenclicker.accessibility.ClickerAccessibilityService
import com.screenclicker.capture.CaptureResult
import com.screenclicker.model.ClickMode
import com.screenclicker.model.PxRect
import com.screenclicker.model.Rule
import com.screenclicker.store.ScriptStore
import com.screenclicker.ui.openAccessibilitySettings
import com.screenclicker.vision.GrayImage
import com.screenclicker.vision.TemplateMatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Rule editor over the live app — as FLOATING WINDOWS, not one fullscreen blocker:
 *
 *  - one small "bubble" window per rectangle (search / template / click), hugging its
 *    rect plus a handle margin; drag the body to move, drag handles to resize;
 *  - one draggable toolbar bubble with role selector, Capture, Check match, Switch
 *    app (accessibility Recents action), Save, Cancel and a status line.
 *
 * Everything outside these windows passes through untouched, so the user can switch
 * apps and use the phone while configuring — the windows persist above whatever app
 * comes up. The active rect's bubble is interactive; inactive bubbles are drawn dim
 * and marked FLAG_NOT_TOUCHABLE.
 *
 * Template capture subtlety: takeScreenshot captures EVERYTHING on screen, including
 * our own windows. All editor windows go INVISIBLE for the capture and are restored
 * right after — the crop then contains only the target app.
 */
class ConfigOverlayService : Service() {

    companion object {
        private const val TAG = "ConfigOverlay"
        private const val CHANNEL_ID = "overlay"
        private const val NOTIFICATION_ID = 41

        fun start(context: Context, ruleId: String) {
            context.startForegroundService(
                Intent(context, ConfigOverlayService::class.java)
                    .putExtra(EXTRA_RULE_ID, ruleId),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ConfigOverlayService::class.java))
        }

        /** Emits the saved Rule whenever the overlay Save button succeeds. */
        val savedRules = MutableStateFlow<Rule?>(null)

        const val EXTRA_RULE_ID = "ruleId"

        private const val SERVICE_POLL_MS = 1_500L
    }

    private class Bubble(val view: RectBubbleView, val params: WindowManager.LayoutParams)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var store: ScriptStore
    private lateinit var windowManager: WindowManager

    private val bubbles = mutableMapOf<RectBubbleView.Role, Bubble>()
    private var toolbar: ConfigToolbarView? = null
    private var toolbarParams: WindowManager.LayoutParams? = null

    private val rects = mutableMapOf<RectBubbleView.Role, PxRect>()
    private var activeRole = RectBubbleView.Role.SEARCH

    private var rule: Rule? = null
    private var ruleScriptId: String? = null
    private var busy = false

    /**
     * Watches the accessibility service state while the editor is up. It is what takes
     * the screenshot and performs the taps, and Android switches it off whenever the
     * app is reinstalled/updated — so the user needs to see that here, live, rather
     * than infer it from a failed capture.
     */
    private val uiHandler = Handler(Looper.getMainLooper())
    private var lastServiceStateKey: String? = null
    private val serviceWatchdog = object : Runnable {
        override fun run() {
            syncServiceState()
            uiHandler.postDelayed(this, SERVICE_POLL_MS)
        }
    }

    private val marginPx: Int by lazy { (20 * resources.displayMetrics.density).toInt() }
    private val toolbarWidthPx: Int by lazy { (300 * resources.displayMetrics.density).toInt() }

    /**
     * Height of the status bar. The editor's toolbar used to be placed at y=80px, which is
     * inside the status bar on most phones — the system swallows touches there, so its
     * buttons were hard or impossible to hit. Everything now positions itself below this.
     */
    private val statusBarInset: Int by lazy {
        windowManager.currentWindowMetrics.windowInsets
            .getInsetsIgnoringVisibility(WindowInsets.Type.systemBars())
            .top
    }

    /**
     * Measured correction between the window manager's coordinate origin and the display's
     * (see [calibrateLayout]). Applied to every position write for the editor windows.
     */
    private var originOffsetX = 0
    private var originOffsetY = 0

    /** The toolbar's position in screen coordinates; the offset above maps it to layout. */
    private var toolbarLogicalX = 0
    private var toolbarLogicalY = 0
    private val screenWidth: Int by lazy {
        windowManager.maximumWindowMetrics.bounds.width()
    }
    private val screenHeight: Int by lazy {
        windowManager.maximumWindowMetrics.bounds.height()
    }

    override fun onCreate() {
        super.onCreate()
        store = ScriptStore(this)
        windowManager = getSystemService(WindowManager::class.java)!!
        startInForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val ruleId = intent?.getStringExtra(EXTRA_RULE_ID)
        val script = ruleId?.let { id ->
            store.list().firstOrNull { script -> script.rules.any { it.id == id } }
        }
        if (script == null) {
            Log.w(TAG, "Rule $ruleId not found; closing overlay")
            stopSelf()
            return START_NOT_STICKY
        }
        val requested = script.rules.first { it.id == ruleId }
        // The panel can open the editor for a different rule while one is already up. The
        // windows and rects belong to the previous rule, so they have to go: otherwise
        // Save would write this rule's old rectangles into the new rule.
        if (rule != null && rule?.id != requested.id) {
            Log.i(TAG, "switching editor to rule ${requested.id}; rebuilding windows")
            removeWindows()
            lastServiceStateKey = null
        }
        ruleScriptId = script.id
        rule = requested
        if (bubbles.isEmpty()) {
            initRects()
            buildWindows()
            scheduleCalibration()
        }
        if (bubbles.isNotEmpty()) syncServiceState()
        uiHandler.removeCallbacks(serviceWatchdog)
        uiHandler.post(serviceWatchdog)
        return START_NOT_STICKY
    }

    /** Diffed so the toolbar only updates on real transitions. */
    private fun syncServiceState() {
        val enabled = ClickerAccessibilityService.isEnabled(this)
        val running = ClickerAccessibilityService.isRunning
        val key = "$enabled/$running"
        if (key == lastServiceStateKey) return
        lastServiceStateKey = key
        val toolbar = toolbar ?: return
        toolbar.setCaptureEnabled(running)
        when {
            !enabled -> toolbar.setAlert(getString(R.string.overlay_service_off), showEnableAction = true)
            !running -> toolbar.setAlert(getString(R.string.overlay_service_connecting))
            else -> {
                toolbar.setAlert(null)
                toolbar.setStatus(getString(R.string.overlay_service_connected))
            }
        }
    }

    private fun initRects() {
        val current = rule ?: return
        fun defaultRect(fraction: Float): PxRect {
            val w = (screenWidth * fraction).toInt().coerceAtLeast(60)
            val h = (screenHeight * fraction).toInt().coerceAtLeast(60)
            val left = ((screenWidth - w) / 2).coerceAtLeast(0)
            val top = ((screenHeight - h) / 2).coerceAtLeast(0)
            return PxRect(left, top, (left + w).coerceAtMost(screenWidth), (top + h).coerceAtMost(screenHeight))
        }
        // Rule rects may come from a different resolution; clamp before use.
        rects[RectBubbleView.Role.SEARCH] =
            if (current.searchRegion.width > 0) current.searchRegion.clampedTo(screenWidth, screenHeight)
            else defaultRect(0.5f)
        rects[RectBubbleView.Role.CLICK] =
            if ((current.clickRegion?.width ?: 0) > 0) current.clickRegion!!.clampedTo(screenWidth, screenHeight)
            else defaultRect(0.2f)
        rects[RectBubbleView.Role.TEMPLATE] = defaultRect(0.25f)
    }

    private fun buildWindows() {
        for (role in RectBubbleView.Role.entries) addBubble(role)
        addToolbar()
        refreshRoles()
    }

    private fun addBubble(role: RectBubbleView.Role) {
        val rect = rects.getValue(role)
        val view = RectBubbleView(this, role, marginPx).apply {
            setSize(rect.width, rect.height)
            label = labelFor(role)
            active = role == activeRole
            callbacks = bubbleCallbacks
        }
        val params = WindowManager.LayoutParams(
            rect.width + 2 * marginPx,
            rect.height + 2 * marginPx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // NO_LIMITS: a rect near the screen edge makes its margin overhang, and
            // without it the window (and its handles) would be clipped at the edge.
            // LAYOUT_IN_SCREEN makes the window's coordinate space the whole display, so
            // a rect coordinate means the same thing here as it does in a screenshot and
            // in dispatchGesture. Without it the origin can sit below the status bar and
            // every box, crop and tap is silently shifted by that much.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = rect.left - marginPx
            y = rect.top - marginPx
        }
        windowManager.addView(view, params)
        bubbles[role] = Bubble(view, params)
    }

    private fun labelFor(role: RectBubbleView.Role): String = when (role) {
        RectBubbleView.Role.SEARCH -> getString(R.string.overlay_role_search)
        RectBubbleView.Role.TEMPLATE -> getString(R.string.overlay_role_template)
        RectBubbleView.Role.CLICK -> getString(R.string.overlay_role_click)
    }

    private fun addToolbar() {
        val view = ConfigToolbarView(this, toolbarCallbacks)
        val density = resources.displayMetrics.density
        val params = WindowManager.LayoutParams(
            toolbarWidthPx,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (screenWidth - toolbarWidthPx - 12 * density).toInt()
            y = statusBarInset + (12 * density).toInt()
        }
        windowManager.addView(view, params)
        toolbar = view
        toolbarParams = params
        toolbarLogicalX = params.x
        toolbarLogicalY = params.y
    }

    /**
     * Positions a bubble window so its rect lands exactly on the model rect.
     *
     * The window manager does not always agree with the display about where (0,0) is:
     * on some devices an overlay's y is measured from the bottom of the status bar, which
     * silently shifts the drawn rectangle — and therefore the captured crop and the taps —
     * by that amount. Instead of guessing the rule, [calibrateLayout] measures the real
     * difference once and it is applied here on every position write.
     */
    private fun applyRectToBubble(role: RectBubbleView.Role) {
        val bubble = bubbles[role] ?: return
        val rect = rects.getValue(role)
        bubble.params.x = rect.left - marginPx + originOffsetX
        bubble.params.y = rect.top - marginPx + originOffsetY
        bubble.params.width = rect.width + 2 * marginPx
        bubble.params.height = rect.height + 2 * marginPx
        runCatching { windowManager.updateViewLayout(bubble.view, bubble.params) }
    }

    private fun applyToolbarPosition() {
        val params = toolbarParams ?: return
        val view = toolbar ?: return
        params.x = toolbarLogicalX + originOffsetX
        params.y = toolbarLogicalY + originOffsetY
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    /**
     * Arms the one-time origin measurement for after the first real layout pass.
     *
     * Not `View.post`: that can run before the view has been through a traversal, where
     * getLocationOnScreen still returns the pre-layout frame — and a bogus "correction"
     * would then be baked into every window for the rest of the session.
     */
    private fun scheduleCalibration() {
        val anchor = bubbles.values.firstOrNull()?.view ?: return
        val observer = object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (anchor.viewTreeObserver.isAlive) {
                    anchor.viewTreeObserver.removeOnGlobalLayoutListener(this)
                }
                anchor.post { calibrateLayout() }
            }
        }
        anchor.viewTreeObserver.addOnGlobalLayoutListener(observer)
    }

    /**
     * Compares where the windows really landed (getLocationOnScreen, i.e. display
     * coordinates) with where the model says they should be, and keeps the difference as
     * a permanent correction. This is what guarantees "the box you see is the pixels that
     * get cropped and the spot that gets tapped" on any OEM's window manager.
     *
     * The correction is the *most common* delta among the windows rather than the first
     * one found: a rectangle hugging a screen edge can have its margin overhang clamped,
     * which would otherwise poison the shared offset for every other window.
     */
    private fun calibrateLayout(attempt: Int = 0) {
        if (bubbles.isEmpty()) return
        val targets = ArrayList<Triple<View, WindowManager.LayoutParams, String>>()
        for ((role, bubble) in bubbles) targets += Triple(bubble.view, bubble.params, role.name)
        toolbar?.let { view -> toolbarParams?.let { p -> targets += Triple(view, p, "toolbar") } }

        val location = IntArray(2)
        val votes = HashMap<Pair<Int, Int>, Int>()
        for ((view, params, name) in targets) {
            view.getLocationOnScreen(location)
            val dx = location[0] - params.x
            val dy = location[1] - params.y
            if (dx == 0 && dy == 0) continue
            if (abs(dx) >= screenWidth || abs(dy) >= screenHeight) {
                Log.w(TAG, "implausible window offset for $name ($dx,$dy); ignoring")
                continue
            }
            Log.i(TAG, "window origin differs from display for $name by ($dx,$dy)")
            val key = dx to dy
            votes[key] = (votes[key] ?: 0) + 1
        }
        val chosen = votes.maxByOrNull { it.value }?.key ?: return
        originOffsetX += chosen.first
        originOffsetY += chosen.second
        for ((role, _) in bubbles) applyRectToBubble(role)
        applyToolbarPosition()
        if (attempt == 0) {
            setStatus("Screen alignment corrected by ($originOffsetX,$originOffsetY)")
        }
        // Verify the correction held; a second pass catches a stale-layout race.
        if (attempt < 2) uiHandler.post { calibrateLayout(attempt + 1) }
    }

    private val bubbleCallbacks = object : RectBubbleView.Callbacks {
        override fun onDragDelta(role: RectBubbleView.Role, mode: RectBubbleView.Mode, dx: Int, dy: Int) {
            if (busy) return
            val before = rects.getValue(role)
            val updated = applyDrag(before, mode, dx, dy)
            if (updated == before) return
            rects[role] = updated
            bubbles.getValue(role).view.setSize(updated.width, updated.height)
            applyRectToBubble(role)
        }

        override fun onDragEnded(role: RectBubbleView.Role) = Unit
    }

    /** Same non-inverting resize/move rules as before, as deltas from the current rect. */
    private fun applyDrag(rect: PxRect, mode: RectBubbleView.Mode, dx: Int, dy: Int): PxRect {
        val min = 16
        val l = rect.left
        val t = rect.top
        val r = rect.right
        val b = rect.bottom
        return when (mode) {
            RectBubbleView.Mode.MOVE -> PxRect(
                (l + dx).coerceIn(0, screenWidth - rect.width),
                (t + dy).coerceIn(0, screenHeight - rect.height),
                (r + dx).coerceIn(rect.width, screenWidth),
                (b + dy).coerceIn(rect.height, screenHeight),
            )

            RectBubbleView.Mode.NW -> PxRect((l + dx).coerceIn(0, r - min), (t + dy).coerceIn(0, b - min), r, b)
            RectBubbleView.Mode.N -> PxRect(l, (t + dy).coerceIn(0, b - min), r, b)
            RectBubbleView.Mode.S -> PxRect(l, t, r, (b + dy).coerceIn(t + min, screenHeight))
            RectBubbleView.Mode.W -> PxRect((l + dx).coerceIn(0, r - min), t, r, b)
            RectBubbleView.Mode.E -> PxRect(l, t, (r + dx).coerceIn(l + min, screenWidth), b)
            RectBubbleView.Mode.NE -> PxRect(l, (t + dy).coerceIn(0, b - min), (r + dx).coerceIn(l + min, screenWidth), b)
            RectBubbleView.Mode.SE -> PxRect(l, t, (r + dx).coerceIn(l + min, screenWidth), (b + dy).coerceIn(t + min, screenHeight))
            RectBubbleView.Mode.SW -> PxRect((l + dx).coerceIn(0, r - min), t, r, (b + dy).coerceIn(t + min, screenHeight))
            RectBubbleView.Mode.NONE -> rect
        }
    }

    private val toolbarCallbacks = object : ConfigToolbarView.Callbacks {
        override fun onRoleSelected(role: RectBubbleView.Role) {
            if (activeRole == role) return
            activeRole = role
            refreshRoles()
        }

        override fun onCaptureTemplate() = captureTemplate()
        override fun onCheckMatch() = checkMatch()
        override fun onSwitchApp() {
            ClickerAccessibilityService.goRecents()
            toolbar?.setStatus(getString(R.string.overlay_pick_app_hint))
        }

        override fun onEnableAccess() {
            runCatching { openAccessibilitySettings() }
            setStatus(getString(R.string.overlay_enable_hint))
        }

        override fun onSave() = saveAndClose()
        override fun onCancel() = stopSelf()

        override fun onMove(dx: Int, dy: Int) {
            if (toolbarParams == null) return
            val density = resources.displayMetrics.density
            val edge = (24 * density).toInt()
            // Clamped to below the status bar: a toolbar tucked under it cannot be tapped.
            toolbarLogicalX = (toolbarLogicalX + dx)
                .coerceIn(-(toolbarWidthPx - edge), screenWidth - edge)
            toolbarLogicalY = (toolbarLogicalY + dy)
                .coerceIn(statusBarInset, screenHeight - edge)
            applyToolbarPosition()
        }
    }

    /**
     * Applies active/inactive state. Re-adds the active bubble (and toolbar) so they
     * stack above inactive ones — WindowManager has no bring-to-front.
     */
    private fun refreshRoles() {
        for ((role, bubble) in bubbles) {
            val isActive = role == activeRole
            bubble.view.active = isActive
            bubble.params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                (if (!isActive) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0)
            windowManager.updateViewLayout(bubble.view, bubble.params)
        }
        val activeBubble = bubbles[activeRole] ?: return
        runCatching { windowManager.removeView(activeBubble.view) }
        windowManager.addView(activeBubble.view, activeBubble.params)
        toolbar?.let { view ->
            runCatching { windowManager.removeView(view) }
            windowManager.addView(view, toolbarParams)
        }
    }

    private fun setStatus(text: String) {
        toolbar?.setStatus(text)
    }

    /**
     * Capture while every editor window is hidden, so our own rectangles never appear
     * in the pixels. The capture may sit out the system rate-limit window with the
     * editor hidden — up to ~1.1s — which reads as a brief blink.
     */
    private suspend fun hiddenCapture(): CaptureResult? {
        val views = bubbles.values.map { it.view } + listOfNotNull(toolbar)
        withContext(Dispatchers.Main) {
            views.forEach { it.visibility = View.INVISIBLE }
            // Every other overlay window has to go too, or its pixels end up in the
            // template: the floating control panel is a separate service's window and
            // the detection layer belongs to the runner.
            ControlPanelService.setMuted(true)
            ClickerAccessibilityService.setDetectionLayerVisible(false)
        }
        try {
            delay(200) // let the compositor drop the hidden frame before requesting
            return ClickerAccessibilityService.captureScreen()
        } finally {
            withContext(Dispatchers.Main) {
                views.forEach { it.visibility = View.VISIBLE }
                ControlPanelService.setMuted(false)
                ClickerAccessibilityService.setDetectionLayerVisible(true)
            }
        }
    }

    /** The exact pixels that were just saved as the template. */
    private fun cropBitmap(result: CaptureResult.Success, region: PxRect): Bitmap {
        val full = Bitmap.createBitmap(result.argb, result.width, result.height, Bitmap.Config.ARGB_8888)
        return Bitmap.createBitmap(full, region.left, region.top, region.width, region.height)
    }

    /**
     * Refuses early with a message that says what to do, instead of failing deep inside
     * the capture path. Returns true when capture may proceed.
     */
    private fun requireService(): Boolean {
        if (ClickerAccessibilityService.isRunning) return true
        lastServiceStateKey = null // force the alert strip to re-evaluate right now
        syncServiceState()
        return false
    }

    private fun captureTemplate() {
        if (busy || !requireService()) return
        busy = true
        scope.launch {
            try {
                when (val result = hiddenCapture()) {
                    is CaptureResult.Success -> {
                        val region = (rects[RectBubbleView.Role.TEMPLATE] ?: return@launch)
                            .clampedTo(result.width, result.height)
                        if (region.width < 4 || region.height < 4) {
                            setStatus("Capture area is too small")
                            return@launch
                        }
                        val ruleId = rule?.id ?: return@launch
                        // One crop, built off the main thread, used for both the saved
                        // template and the preview (this used to build the full-screen
                        // bitmap twice, once of them on the UI thread).
                        val crop = withContext(Dispatchers.Default) { cropBitmap(result, region) }
                        val name = withContext(Dispatchers.Default) {
                            store.saveTemplateFromBitmap(ruleId, crop)
                        }
                        rule = rule?.copy(templateFile = name)
                        // Geometry is logged and shown next to the preview: if the
                        // screenshot is not the size of the display, its origin is not
                        // the display's either, and that is worth knowing immediately.
                        if (result.width != screenWidth || result.height != screenHeight) {
                            Log.w(
                                TAG,
                                "screenshot ${result.width}x${result.height} differs from " +
                                    "display ${screenWidth}x$screenHeight",
                            )
                        }
                        toolbar?.setTemplatePreview(crop, "${region.width} x ${region.height} px")
                        setStatus("Template captured (${region.width}x${region.height})")
                    }

                    is CaptureResult.Failure -> setStatus("Capture failed: ${result.reason}")
                    null -> setStatus("Capture unavailable")
                }
            } finally {
                busy = false
            }
        }
    }

    private fun checkMatch() {
        val current = rule ?: return
        if (busy || !requireService()) return
        busy = true
        scope.launch {
            try {
                val template = withContext(Dispatchers.Default) { store.loadTemplate(current) }
                if (template == null) {
                    setStatus("No template captured yet")
                    return@launch
                }
                when (val result = hiddenCapture()) {
                    is CaptureResult.Success -> {
                        // Same trick as the runner: convert and search only the search
                        // region, off the main thread — a full-screen grayscale here was
                        // a visible freeze on every Check.
                        val region = (rects[RectBubbleView.Role.SEARCH] ?: return@launch)
                            .clampedTo(result.width, result.height)
                        if (region.width < template.width || region.height < template.height) {
                            setStatus("Search area is smaller than the template")
                            return@launch
                        }
                        val match = withContext(Dispatchers.Default) {
                            val scan = GrayImage.fromArgbRegion(
                                result.argb,
                                result.width,
                                result.height,
                                region,
                            )
                            TemplateMatcher.findBest(
                                scan,
                                template,
                                PxRect(0, 0, region.width, region.height),
                                0f,
                            )
                        }
                        setStatus(
                            match?.let {
                                val left = it.left + region.left
                                val top = it.top + region.top
                                "Best match ${(it.score * 100).toInt()}% at ($left,$top)" +
                                    " — threshold ${(current.threshold * 100).toInt()}%"
                            } ?: "Nothing found",
                        )
                    }

                    is CaptureResult.Failure -> setStatus("Capture failed: ${result.reason}")
                    null -> setStatus("Capture unavailable")
                }
            } finally {
                busy = false
            }
        }
    }

    /** Removes every editor window; the model rects survive for a rebuild. */
    private fun removeWindows() {
        for (bubble in bubbles.values) {
            runCatching { windowManager.removeView(bubble.view) }
        }
        bubbles.clear()
        toolbar?.let { runCatching { windowManager.removeView(it) } }
        toolbar = null
        toolbarParams = null
    }

    private fun saveAndClose() {
        val current = rule ?: return
        var updated = current.copy(searchRegion = rects[RectBubbleView.Role.SEARCH] ?: current.searchRegion)
        if (updated.clickMode == ClickMode.ON_REGION) {
            updated = updated.copy(clickRegion = rects[RectBubbleView.Role.CLICK] ?: updated.clickRegion)
        }
        val scriptId = ruleScriptId
        if (scriptId != null) {
            val script = store.list().firstOrNull { it.id == scriptId }
            if (script != null) {
                store.save(script.withRule(updated))
                savedRules.value = updated
            }
        }
        stopSelf()
    }

    private fun startInForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_overlay),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = getString(R.string.channel_overlay_description) },
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.overlay_notification_text))
            .setOngoing(true)
            .build()
        // FOREGROUND_SERVICE_TYPE_SPECIAL_USE only exists on API 34+; passing it to
        // startForeground on 11-13 throws IllegalArgumentException.
        if (Build.VERSION.SDK_INT >= 34) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        uiHandler.removeCallbacks(serviceWatchdog)
        removeWindows()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

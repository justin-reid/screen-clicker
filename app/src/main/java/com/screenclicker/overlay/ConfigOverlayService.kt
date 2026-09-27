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
import androidx.core.app.ServiceCompat
import com.screenclicker.R
import com.screenclicker.accessibility.ClickerAccessibilityService
import com.screenclicker.capture.CaptureResult
import com.screenclicker.capture.Capturers
import com.screenclicker.model.ClickMode
import com.screenclicker.model.PxRect
import com.screenclicker.model.Rule
import com.screenclicker.store.ScriptStore
import com.screenclicker.store.SettingsRepo
import com.screenclicker.ui.openAccessibilitySettings
import com.screenclicker.vision.Confidence
import com.screenclicker.vision.GrayImage
import com.screenclicker.vision.TemplateMatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The rule editor, drawn over the live app as FLOATING windows rather than a fullscreen
 * blocker:
 *
 *  - ONE rectangle window at a time, for whichever area the toolbar has selected. Nothing
 *    is shown until one is picked: three overlapping boxes over the app you are trying to
 *    read is unusable, and an empty screen is the honest starting state.
 *  - one draggable toolbar with the area selector, Capture, Check, Switch app
 *    (accessibility Recents action), Save, Cancel and a status line.
 *
 * Everything outside those windows passes through untouched, so the user can switch apps
 * and use the phone while configuring.
 *
 * ## Captures are measured, never computed
 *
 * Overlay window coordinates and screenshot coordinates do not reliably share an origin:
 * an overlay's `y` can be measured from below the status bar, and on a foldable the window
 * manager may lay windows out against a differently sized display than the service assumes.
 * Either way the crop lands somewhere other than the drawn rectangle, by a constant offset
 * that no API here exposes — and moving the windows to compensate is worse, because the
 * window manager then applies its own offset on top.
 *
 * So every capture measures it instead. The rectangle's handle margin carries a small
 * magenta/cyan checkerboard ([ProbeMarker], deliberately outside the cropped area) which is
 * located in the screenshot itself, in the screenshot's own coordinates. The difference
 * from where the model expected it is applied to this crop, to the region Check searches,
 * and — recorded on the rule as [Rule.alignX]/[Rule.alignY] — to what the engine searches
 * and taps at run time. The window manager is never fought: the rectangle is drawn wherever
 * it lands, and everything else follows those pixels.
 */
class ConfigOverlayService : Service() {

    companion object {
        private const val TAG = "ConfigOverlay"
        private const val CHANNEL_ID = "overlay"
        private const val NOTIFICATION_ID = 41
        private const val SERVICE_POLL_MS = 1_500L

        /** Let the compositor show the probe-only frame before asking for the screenshot. */
        private const val PROBE_SETTLE_MS = 200L

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

        @Volatile
        private var instance: ConfigOverlayService? = null

        /**
         * Hides the editor's windows *and* takes them out of the touch path while the system
         * installer is on screen. A rectangle can cover most of the screen and draws above a
         * normal app's dialog, so left alone it would swallow the taps meant for the
         * installer's Update button; hiding the view is not enough, the window keeps its size.
         */
        fun setStandDown(standDown: Boolean) {
            instance?.setStandDownInternal(standDown)
        }

        const val EXTRA_RULE_ID = "ruleId"
    }

    private class Bubble(val view: RectBubbleView, val params: WindowManager.LayoutParams)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var store: ScriptStore
    private lateinit var settingsRepo: SettingsRepo
    private lateinit var windowManager: WindowManager

    /** At most one rectangle window exists at a time. */
    private var bubble: Bubble? = null
    private var activeRole: RectBubbleView.Role? = null

    private var toolbar: ConfigToolbarView? = null
    private var toolbarParams: WindowManager.LayoutParams? = null
    private var toolbarLogicalX = 0
    private var toolbarLogicalY = 0

    /** True while the system installer owns the screen; see [applyWindowState]. */
    private var standDown = false

    private val rects = mutableMapOf<RectBubbleView.Role, PxRect>()

    private var rule: Rule? = null
    private var ruleScriptId: String? = null
    private var busy = false

    // --- alignment, in two coordinate spaces (see the class doc) ---

    // As the rule arrived: screenshot space and display space, from an earlier session.
    private var ruleAlignX = 0
    private var ruleAlignY = 0
    private var ruleTapAlignX = 0
    private var ruleTapAlignY = 0

    /** Measured this session: a successful probe, and the window manager's own offset. */
    private var probedX: Int? = null
    private var probedY: Int? = null
    private var windowX: Int? = null
    private var windowY: Int? = null

    /**
     * The most recent capture: its size and backend. Recorded on the rule only when that
     * capture also produced the rule's template (see [templateCapturedThisSession]).
     */
    private var lastFrameWidth = 0
    private var lastFrameHeight = 0
    private var lastBackend = ""

    /**
     * True once this session has written a NEW template PNG for the rule.
     *
     * Alignment and frame metadata may only be persisted together with the template they
     * describe. A session that merely checked a match did measure a capture, but it did not
     * produce the rule's template: overwriting the recorded alignment or frame size then would
     * leave the engine searching a region offset from the template's own content (or matching
     * across a frame-size change it knows nothing about).
     */
    private var templateCapturedThisSession = false

    /**
     * Correction for screenshot-space coordinates (the crop, the search region): a successful
     * probe, else the window offset, else what the rule was saved with. The fallback is
     * deliberately *not* persisted — it would overwrite a value an earlier session measured
     * properly with something weaker.
     */
    private val alignX: Int get() = probedX ?: windowX ?: ruleAlignX
    private val alignY: Int get() = probedY ?: windowY ?: ruleAlignY

    /** Correction for display-space coordinates: taps and detection highlights. */
    private val tapAlignX: Int get() = windowX ?: ruleTapAlignX
    private val tapAlignY: Int get() = windowY ?: ruleTapAlignY

    private val probedThisSession: Boolean get() = probedX != null

    private val uiHandler = Handler(Looper.getMainLooper())
    private var lastServiceStateKey: String? = null
    private val serviceWatchdog = object : Runnable {
        override fun run() {
            syncServiceState()
            uiHandler.postDelayed(this, SERVICE_POLL_MS)
        }
    }

    private val density: Float by lazy { resources.displayMetrics.density }
    /**
     * Handle margin. Must stay wider than the alignment probe, which lives in it and must
     * never reach inside the area that gets cropped.
     */
    private val marginPx: Int by lazy {
        (20 * density).toInt().coerceAtLeast(ProbeMarker.SIZE + 4)
    }
    private val toolbarWidthPx: Int by lazy { (300 * density).toInt() }

    /** Live values: re-read because a foldable can change the display under us. */
    private var screenWidth = 0
    private var screenHeight = 0

    private val statusBarInset: Int by lazy {
        windowManager.currentWindowMetrics.windowInsets
            .getInsetsIgnoringVisibility(WindowInsets.Type.systemBars())
            .top
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        store = ScriptStore(this)
        settingsRepo = SettingsRepo(this)
        windowManager = getSystemService(WindowManager::class.java)!!
        refreshScreenMetrics()
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
        refreshScreenMetrics()

        val requested = script.rules.first { it.id == ruleId }
        // The floating panel can open the editor for another rule while one is already up.
        // The windows and rects belong to the previous rule, so they have to go: otherwise
        // Save would write this rule's old rectangles into the new rule.
        val previousRole = activeRole
        val switched = rule != null && rule?.id != requested.id
        if (switched) {
            Log.i(TAG, "switching editor to rule ${requested.id}")
            removeBubble()
        }
        ruleScriptId = script.id
        rule = requested
        initRects()

        if (toolbar == null) addToolbar()
        // Nothing is drawn until an area is chosen (see the class doc). A role that was
        // selected before a rule switch comes back, on the new rule's rectangles.
        activeRole = null
        toolbar?.setActiveRole(null)
        // Re-show whatever was on screen, against the freshly initialised rects. Re-starting
        // the editor for the same rule (or switching rules) must not leave an orphaned
        // rectangle behind that the toolbar claims is not there.
        if (previousRole != null) showRole(previousRole)
        syncServiceState()

        uiHandler.removeCallbacks(serviceWatchdog)
        uiHandler.post(serviceWatchdog)
        return START_NOT_STICKY
    }

    private fun refreshScreenMetrics() {
        val bounds = windowManager.currentWindowMetrics.bounds
        screenWidth = bounds.width()
        screenHeight = bounds.height()
    }

    /**
     * Refreshes the display size from an actual screenshot. The window manager's idea of the
     * screen and the size of a screenshot disagree on foldables; the screenshot is the one
     * that matters for regions, so it wins.
     */
    private fun adoptMeasuredScreenSize(width: Int, height: Int) {
        if (width == screenWidth && height == screenHeight) return
        Log.w(
            TAG,
            "display is ${screenWidth}x$screenHeight but screenshots are ${width}x$height; " +
                "using the screenshot size for regions",
        )
        screenWidth = width
        screenHeight = height
    }

    private fun initRects() {
        val current = rule ?: return
        ruleAlignX = current.alignX
        ruleAlignY = current.alignY
        ruleTapAlignX = current.tapAlignX
        ruleTapAlignY = current.tapAlignY
        lastFrameWidth = current.frameWidth
        lastFrameHeight = current.frameHeight
        lastBackend = current.captureBackend
        templateCapturedThisSession = false
        probedX = null
        probedY = null
        windowX = null
        windowY = null

        fun defaultRect(fraction: Float): PxRect {
            val w = (screenWidth * fraction).toInt().coerceAtLeast(60)
            val h = (screenHeight * fraction).toInt().coerceAtLeast(60)
            val left = ((screenWidth - w) / 2).coerceAtLeast(0)
            val top = ((screenHeight - h) / 2).coerceAtLeast(0)
            return PxRect(
                left,
                top,
                (left + w).coerceAtMost(screenWidth),
                (top + h).coerceAtMost(screenHeight),
            )
        }
        // Rule rects may come from another resolution; clamp before use.
        rects[RectBubbleView.Role.SEARCH] =
            if (current.searchRegion.width > 0) {
                current.searchRegion.clampedTo(screenWidth, screenHeight)
            } else {
                defaultRect(0.5f)
            }
        rects[RectBubbleView.Role.CLICK] =
            if ((current.clickRegion?.width ?: 0) > 0) {
                current.clickRegion!!.clampedTo(screenWidth, screenHeight)
            } else {
                defaultRect(0.2f)
            }
        rects[RectBubbleView.Role.TEMPLATE] =
            if ((current.templateRegion?.width ?: 0) > 0) {
                current.templateRegion!!.clampedTo(screenWidth, screenHeight)
            } else {
                defaultRect(0.25f)
            }
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
            !enabled -> toolbar.setAlert(
                getString(R.string.overlay_service_off),
                showEnableAction = true,
            )

            !running -> toolbar.setAlert(getString(R.string.overlay_service_connecting))
            else -> {
                toolbar.setAlert(null)
                toolbar.setStatus(getString(R.string.overlay_service_connected))
            }
        }
    }

    private fun labelFor(role: RectBubbleView.Role): String = when (role) {
        RectBubbleView.Role.SEARCH -> getString(R.string.overlay_role_search)
        RectBubbleView.Role.TEMPLATE -> getString(R.string.overlay_role_template)
        RectBubbleView.Role.CLICK -> getString(R.string.overlay_role_click)
    }

    /**
     * Shows the rectangle for [role] and hides any other. Passing the current role, or null,
     * hides everything — the toolbar's buttons toggle.
     */
    private fun showRole(role: RectBubbleView.Role?) {
        val next = if (role == activeRole) null else role
        activeRole = next
        val existing = bubble
        if (existing != null && existing.view.role == next) {
            applyRectToBubble()
        } else {
            existing?.let { runCatching { windowManager.removeView(it.view) } }
            bubble = null
            if (next != null) addBubble(next)
        }
        toolbar?.setActiveRole(next)
        // Re-adding the toolbar keeps it above the rectangle where they overlap;
        // WindowManager has no bring-to-front.
        toolbar?.let { view ->
            toolbarParams?.let { params ->
                runCatching { windowManager.removeView(view) }
                runCatching { windowManager.addView(view, params) }
                applyWindowState()
            }
        }
    }

    private fun addBubble(role: RectBubbleView.Role) {
        val rect = rects.getValue(role)
        val view = RectBubbleView(this, role, marginPx).apply {
            setSize(rect.width, rect.height)
            label = labelFor(role)
            active = true
            callbacks = bubbleCallbacks
            probeOffset = ProbeMarker.offsetInWindow(rect, marginPx, screenWidth, screenHeight)
        }
        val params = WindowManager.LayoutParams(
            rect.width + 2 * marginPx,
            rect.height + 2 * marginPx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // LAYOUT_IN_SCREEN: the window's coordinates are display coordinates, like the
            // screenshot's. LAYOUT_NO_LIMITS: a rect at the screen edge makes its handle
            // margin overhang, and without it the window (and its handles) would be clipped.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = rect.left - marginPx
            y = rect.top - marginPx
        }
        runCatching { windowManager.addView(view, params) }.onFailure {
            Log.w(TAG, "rectangle window unavailable", it)
            return
        }
        bubble = Bubble(view, params)
        applyWindowState()
    }

    private fun removeBubble() {
        bubble?.let { runCatching { windowManager.removeView(it.view) } }
        bubble = null
    }

    /**
     * Position of a bubble window in screen coordinates if the window manager drew it where
     * we asked. The difference against getLocationOnScreen is a diagnostic: it is the offset
     * in *display* space, which the probe measures in *screenshot* space. If they disagree,
     * screenshot space and display space are not the same thing on this device.
     */
    private fun windowDelta(bubble: Bubble): Pair<Int, Int> {
        val location = IntArray(2)
        bubble.view.getLocationOnScreen(location)
        return (location[0] - bubble.params.x) to (location[1] - bubble.params.y)
    }

    private fun applyRectToBubble() {
        val bubble = bubble ?: return
        val role = bubble.view.role
        val rect = rects[role] ?: return
        bubble.view.setSize(rect.width, rect.height)
        bubble.view.probeOffset =
            ProbeMarker.offsetInWindow(rect, marginPx, screenWidth, screenHeight)
        bubble.params.width = rect.width + 2 * marginPx
        bubble.params.height = rect.height + 2 * marginPx
        bubble.params.x = rect.left - marginPx
        bubble.params.y = rect.top - marginPx
        runCatching { windowManager.updateViewLayout(bubble.view, bubble.params) }
    }

    private val bubbleCallbacks = object : RectBubbleView.Callbacks {
        override fun onDragDelta(
            role: RectBubbleView.Role,
            mode: RectBubbleView.Mode,
            dx: Int,
            dy: Int,
        ) {
            if (busy) return
            val before = rects.getValue(role)
            val updated = applyDrag(before, mode, dx, dy)
            if (updated == before) return
            rects[role] = updated
            applyRectToBubble()
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

    private fun addToolbar() {
        val view = ConfigToolbarView(this, toolbarCallbacks)
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
            x = screenWidth - toolbarWidthPx - (12 * density).toInt()
            y = statusBarInset + (12 * density).toInt()
        }
        runCatching { windowManager.addView(view, params) }.onFailure {
            Log.w(TAG, "editor toolbar unavailable", it)
            stopSelf()
            return
        }
        toolbar = view
        toolbarParams = params
        toolbarLogicalX = params.x
        toolbarLogicalY = params.y
        applyWindowState()
    }

    private val toolbarCallbacks = object : ConfigToolbarView.Callbacks {
        override fun onRoleSelected(role: RectBubbleView.Role) {
            // Switching areas mid-capture would leave the probe frame and the measured
            // rectangle referring to different views.
            if (busy) return
            showRole(role)
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

        override fun onSave() {
            if (busy) return
            saveAndClose()
        }

        override fun onCancel() = stopSelf()

        override fun onMove(dx: Int, dy: Int) {
            if (toolbarParams == null) return
            val edge = (24 * density).toInt()
            // Clamped to below the status bar: a toolbar tucked under it cannot be tapped.
            toolbarLogicalX = (toolbarLogicalX + dx)
                .coerceIn(-(toolbarWidthPx - edge), screenWidth - edge)
            toolbarLogicalY = (toolbarLogicalY + dy)
                .coerceIn(statusBarInset, screenHeight - edge)
            toolbarParams?.let {
                it.x = toolbarLogicalX
                it.y = toolbarLogicalY
                toolbar?.let { view -> runCatching { windowManager.updateViewLayout(view, it) } }
            }
        }
    }

    private fun setStatus(text: String) {
        toolbar?.setStatus(text)
    }

    private fun setStandDownInternal(value: Boolean) {
        if (standDown == value) return
        standDown = value
        applyWindowState()
    }

    /**
     * One place decides whether the editor's windows may be seen and touched. The flag goes
     * on the *window*, not just the view, because a GONE view inside a window that has an
     * explicit size still has that window to receive touches with.
     */
    private fun applyWindowState() {
        val toolbarEntry = toolbar?.let { view -> toolbarParams?.let { params -> view to params } }
        val bubbleEntry = bubble?.let { it.view to it.params }
        for ((view, params) in listOfNotNull(toolbarEntry, bubbleEntry)) {
            view.visibility = if (standDown) View.GONE else View.VISIBLE
            val flags = if (standDown) {
                params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            } else {
                params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            }
            if (flags == params.flags) continue
            params.flags = flags
            runCatching { windowManager.updateViewLayout(view, params) }
        }
    }

    /**
     * Takes one screenshot with the probe visible and locates the marker in it. Returns the
     * capture, or null when capture is unavailable.
     *
     * The toolbar, the floating panel and the detection layer are hidden so their pixels
     * cannot land in the crop, and the rectangle itself switches to probe-only painting —
     * the marker lives in the handle margin, outside the cropped area.
     */
    private suspend fun probeCapture(target: Bubble?): CaptureResult? {
        // The same backend chooser the runner uses: the alignment and the template have to be
        // authored in the space the run will see (a mediaProjection mirror and an accessibility
        // screenshot are not interchangeable — see Rule.frameWidth).
        val capturer = Capturers.pick(settingsRepo.load())
        withContext(Dispatchers.Main) {
            toolbar?.visibility = View.INVISIBLE
            ControlPanelService.setMuted(true)
            ClickerAccessibilityService.setDetectionLayerVisible(false)
            target?.view?.probeOnly = true
        }
        try {
            delay(PROBE_SETTLE_MS) // let the compositor show the probe frame
            val result = capturer.capture()
            if (result is CaptureResult.Success) {
                lastFrameWidth = result.width
                lastFrameHeight = result.height
                lastBackend = capturer.name
            }
            return result
        } finally {
            // NonCancellable: Cancel, Save or a rule switch can destroy the service during
            // the up-to-1.3s this can take. Plain withContext throws on entry for an
            // already-cancelled coroutine — the toolbar, the floating panel and the detection
            // layer would stay hidden with no way back.
            withContext(Dispatchers.Main + NonCancellable) {
                target?.view?.probeOnly = false
                // Not "make it visible": standing down for the installer outranks a finished
                // capture, and applyWindowState knows about both.
                applyWindowState()
                ControlPanelService.setMuted(false)
                ClickerAccessibilityService.setDetectionLayerVisible(true)
            }
        }
    }

    /**
     * True when [target] is still the window on screen. A capture spans a rate-limit window
     * of over a second, and switching areas or rules in that time would otherwise make the
     * probe frame, the measured rectangle and the saved template refer to different things.
     */
    private fun captureStillValid(target: Bubble?): Boolean = target != null && bubble === target

    /**
     * Measures the offset between the editor's model coordinates and the screenshot's, using
     * the probe marker found in [capture]. Falls back to the window manager's own offset when
     * the marker is not in the image (clipped at a screen edge, or covered).
     */
    private fun measureAlignment(capture: CaptureResult.Success): Boolean {
        val bubble = bubble ?: return false
        val rect = rects[bubble.view.role] ?: return false
        val offset = bubble.view.probeOffset
        if (offset == null) {
            // A rectangle flush against both screen edges: no marker can be hidden outside the
            // crop, so fall back to the window manager's own idea of where the window is.
            Log.i(TAG, "no alignment probe for $rect (no visible margin); using window offset")
            setStatus(getString(R.string.overlay_align_fallback))
            return false
        }
        val (expectedX, expectedY) = ProbeMarker.expectedPosition(rect, marginPx, offset)
        val (windowDx, windowDy) = windowDelta(bubble)
        // Always recorded: this is the correction display-space consumers (taps) need.
        windowX = windowDx
        windowY = windowDy

        val found = ProbeMarker.locate(
            capture.argb,
            capture.width,
            capture.height,
            expectedX,
            expectedY,
        )
        if (found == null) {
            Log.w(
                TAG,
                "alignment probe not found (expected $expectedX,$expectedY, window offset " +
                    "($windowDx,$windowDy)); using the window offset for this capture",
            )
            setStatus(getString(R.string.overlay_align_fallback))
            return false
        }

        probedX = found.first - expectedX
        probedY = found.second - expectedY
        Log.i(
            TAG,
            "alignment: screenshot (${probedX},${probedY}) display ($windowDx,$windowDy) — " +
                "expected ($expectedX,$expectedY), found $found",
        )
        return true
    }

    /**
     * Refuses early with a message that says what to do, instead of failing deep inside the
     * capture path. Returns true when capture may proceed.
     */
    private fun requireService(): Boolean {
        if (ClickerAccessibilityService.isRunning) return true
        lastServiceStateKey = null // force the alert strip to re-evaluate right now
        syncServiceState()
        return false
    }

    /** The pixels that were saved as the template, plus where the crop had to be taken. */
    private fun cropBitmap(capture: CaptureResult.Success, region: PxRect): Bitmap {
        val full = Bitmap.createBitmap(
            capture.argb,
            capture.width,
            capture.height,
            Bitmap.Config.ARGB_8888,
        )
        return Bitmap.createBitmap(full, region.left, region.top, region.width, region.height)
    }

    /** Ensures an area is on screen so a capture has a rectangle to measure against. */
    private fun ensureRoleVisible(role: RectBubbleView.Role): Boolean {
        if (activeRole == role) return true
        showRole(role)
        return false
    }

    private fun captureTemplate() {
        if (busy || !requireService()) return
        if (!ensureRoleVisible(RectBubbleView.Role.TEMPLATE)) {
            setStatus(
                getString(
                    R.string.overlay_show_area_first,
                    getString(R.string.overlay_role_template),
                    getString(R.string.overlay_capture),
                ),
            )
            return
        }
        val target = bubble
        // The frame size the rule was previously captured at, so a change (fold, backend
        // switch) can be pointed out while the user is looking at the capture.
        val previousFrameWidth = rule?.frameWidth ?: 0
        val previousFrameHeight = rule?.frameHeight ?: 0
        busy = true
        scope.launch {
            try {
                when (val result = probeCapture(target)) {
                    is CaptureResult.Success -> {
                        if (!captureStillValid(target)) {
                            setStatus(getString(R.string.overlay_capture_changed))
                            return@launch
                        }
                        adoptMeasuredScreenSize(result.width, result.height)
                        val probed = measureAlignment(result)
                        // The crop is the drawn rectangle translated by the measured
                        // alignment: exactly the pixels inside the box, whatever the window
                        // manager did with the window.
                        val region = (rects[RectBubbleView.Role.TEMPLATE] ?: return@launch)
                            .translated(alignX, alignY)
                            .clampedTo(result.width, result.height)
                        if (region.width < 4 || region.height < 4) {
                            setStatus("Capture area is too small")
                            return@launch
                        }
                        val ruleId = rule?.id ?: return@launch
                        // One crop, built off the main thread, used for both the saved
                        // template and the preview.
                        val crop = withContext(Dispatchers.Default) { cropBitmap(result, region) }
                        val name = withContext(Dispatchers.Default) {
                            store.saveTemplateFromBitmap(ruleId, crop)
                        }
                        // A crop of nothing but background cannot be located, however well some
                        // patch of screen correlates with it at run time: say so now, while the
                        // image can still be re-captured at full visibility.
                        val locatable = withContext(Dispatchers.Default) {
                            TemplateMatcher.isLocatable(grayOf(crop))
                        }
                        // A new template now exists, and the alignment/frame recorded above are
                        // the ones this crop was taken with, so Save may persist them.
                        templateCapturedThisSession = true
                        rule = rule?.copy(templateFile = name)
                        // A template captured in a different capture space than the rule
                        // records cannot match at run time; say so while the user is here.
                        val frameChanged = previousFrameWidth > 0 && lastFrameWidth > 0 &&
                            (lastFrameWidth != previousFrameWidth ||
                                lastFrameHeight != previousFrameHeight)
                        val frameNote = if (frameChanged) {
                            " — capture size changed from ${previousFrameWidth}x" +
                                "${previousFrameHeight}"
                        } else {
                            ""
                        }
                        val label = buildString {
                            append("${region.width} x ${region.height} px")
                            if (alignX != 0 || alignY != 0) append(" · align $alignX,$alignY")
                            append(frameNote)
                        }
                        toolbar?.setTemplatePreview(crop, label)
                        setStatus(
                            buildString {
                                append("Template captured (${region.width}x${region.height})")
                                if (!probed) append(" — alignment probe not visible")
                                if (!locatable) {
                                    append(" — almost no contrast: re-capture it while the ")
                                    append("image is fully visible")
                                }
                                append(frameNote)
                            },
                        )
                    }

                    is CaptureResult.Failure -> setStatus("Capture failed: ${result.reason}")
                    null -> setStatus("Capture unavailable")
                }
            } catch (e: Exception) {
                // Anything here (a full disk while writing the PNG, a window that vanished)
                // would otherwise kill the process: nothing above catches it.
                if (e !is kotlinx.coroutines.CancellationException) {
                    Log.e(TAG, "template capture failed", e)
                    setStatus("Capture failed: ${e.message ?: e::class.simpleName}")
                }
            } finally {
                busy = false
            }
        }
    }

    /** Match scores read as whole percents wherever they are shown. */
    private fun percent(score: Float): String = "${(score * 100).toInt()}%"

    /** Luma of a captured crop, for the capture-time "can this be located?" check. */
    private fun grayOf(bitmap: Bitmap): GrayImage {
        val argb = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(argb, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return GrayImage.fromArgb(argb, bitmap.width, bitmap.height)
    }

    private fun checkMatch() {
        val current = rule ?: return
        if (busy || !requireService()) return
        if (!ensureRoleVisible(RectBubbleView.Role.SEARCH)) {
            setStatus(
                getString(
                    R.string.overlay_show_area_first,
                    getString(R.string.overlay_role_search),
                    getString(R.string.overlay_check),
                ),
            )
            return
        }
        val target = bubble
        busy = true
        scope.launch {
            try {
                val template = withContext(Dispatchers.Default) { store.loadTemplate(current) }
                if (template == null) {
                    setStatus("No template captured yet")
                    return@launch
                }
                when (val result = probeCapture(target)) {
                    is CaptureResult.Success -> {
                        if (!captureStillValid(target)) {
                            setStatus(getString(R.string.overlay_capture_changed))
                            return@launch
                        }
                        adoptMeasuredScreenSize(result.width, result.height)
                        measureAlignment(result)
                        val region = (rects[RectBubbleView.Role.SEARCH] ?: return@launch)
                            .translated(alignX, alignY)
                            .clampedTo(result.width, result.height)
                        if (region.width < template.width || region.height < template.height) {
                            setStatus("Search area is smaller than the template")
                            return@launch
                        }
                        // Convert and search only the search region, off the main thread.
                        val outcome = withContext(Dispatchers.Default) {
                            val scan = GrayImage.fromArgbRegion(
                                result.argb,
                                result.width,
                                result.height,
                                region,
                            )
                            TemplateMatcher.search(
                                scan,
                                template,
                                PxRect(0, 0, region.width, region.height),
                            )
                        }
                        val best = outcome.best
                        setStatus(
                            when {
                                // Check exists to answer "would Run find this?". A bare score
                                // cannot: a template with nothing in it, or two candidates that
                                // score alike, would read as a find and then behave differently
                                // in Run, which is the report this is here to stop.
                                outcome.confidence == Confidence.NO_CONTRAST ->
                                    "Template has almost no contrast — re-capture it while the " +
                                        "image is fully visible"
                                best == null -> "Nothing found"
                                outcome.confidence == Confidence.AMBIGUOUS ->
                                    "Best ${percent(best.score)} at " +
                                        "(${best.left + region.left},${best.top + region.top}) but a " +
                                        "second candidate scores ${percent(outcome.runnerUp)} — " +
                                        "not a reliable find"
                                else ->
                                    "Best match ${percent(best.score)} at " +
                                        "(${best.left + region.left},${best.top + region.top})" +
                                        " — threshold ${(current.threshold * 100).toInt()}%"
                            },
                        )
                    }

                    is CaptureResult.Failure -> setStatus("Capture failed: ${result.reason}")
                    null -> setStatus("Capture unavailable")
                }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    Log.e(TAG, "check match failed", e)
                    setStatus("Check failed: ${e.message ?: e::class.simpleName}")
                }
            } finally {
                busy = false
            }
        }
    }

    private fun saveAndClose() {
        val current = rule ?: return
        if (!probedThisSession) {
            Log.w(
                TAG,
                "saving without a successful alignment probe; keeping " +
                    "(${current.alignX},${current.alignY})",
            )
        }
        var updated = current.copy(
            // Regions stay in the editor's model space — what the user drew — with the
            // measured alignments stored alongside for the engine to apply.
            searchRegion = rects[RectBubbleView.Role.SEARCH] ?: current.searchRegion,
            templateRegion = rects[RectBubbleView.Role.TEMPLATE] ?: current.templateRegion,
            // A new template was cropped this session, so the value it was cropped with is the
            // one to keep — including the window-offset fallback, since the crop used that too.
            // Without a new template, everything stays exactly as the rule had it.
            alignX = if (templateCapturedThisSession) alignX else current.alignX,
            alignY = if (templateCapturedThisSession) alignY else current.alignY,
            tapAlignX = if (templateCapturedThisSession) tapAlignX else current.tapAlignX,
            tapAlignY = if (templateCapturedThisSession) tapAlignY else current.tapAlignY,
            frameWidth = if (templateCapturedThisSession) lastFrameWidth else current.frameWidth,
            frameHeight =
                if (templateCapturedThisSession) lastFrameHeight else current.frameHeight,
            captureBackend = if (templateCapturedThisSession) lastBackend else current.captureBackend,
        )
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
        removeBubble()
        toolbar?.let { runCatching { windowManager.removeView(it) } }
        toolbar = null
        toolbarParams = null
        if (instance === this) instance = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

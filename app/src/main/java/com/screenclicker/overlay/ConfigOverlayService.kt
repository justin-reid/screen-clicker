package com.screenclicker.overlay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.screenclicker.R
import com.screenclicker.accessibility.ClickerAccessibilityService
import com.screenclicker.capture.CaptureResult
import com.screenclicker.model.ClickMode
import com.screenclicker.model.PxRect
import com.screenclicker.model.Rule
import com.screenclicker.store.ScriptStore
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

    private val marginPx: Int by lazy { (20 * resources.displayMetrics.density).toInt() }
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
        ruleScriptId = script.id
        rule = script.rules.first { it.id == ruleId }
        if (bubbles.isEmpty()) {
            initRects()
            buildWindows()
        }
        return START_NOT_STICKY
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
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
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
        val params = WindowManager.LayoutParams(
            (300 * resources.displayMetrics.density).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenWidth - (320 * resources.displayMetrics.density).toInt()
            y = 80
        }
        windowManager.addView(view, params)
        toolbar = view
        toolbarParams = params
    }

    private val bubbleCallbacks = object : RectBubbleView.Callbacks {
        override fun onDragDelta(role: RectBubbleView.Role, mode: RectBubbleView.Mode, dx: Int, dy: Int) {
            if (busy) return
            val before = rects.getValue(role)
            val updated = applyDrag(before, mode, dx, dy)
            if (updated == before) return
            rects[role] = updated
            val bubble = bubbles.getValue(role)
            bubble.view.setSize(updated.width, updated.height)
            bubble.params.x = updated.left - marginPx
            bubble.params.y = updated.top - marginPx
            bubble.params.width = updated.width + 2 * marginPx
            bubble.params.height = updated.height + 2 * marginPx
            windowManager.updateViewLayout(bubble.view, bubble.params)
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

        override fun onSave() = saveAndClose()
        override fun onCancel() = stopSelf()

        override fun onMove(dx: Int, dy: Int) {
            val params = toolbarParams ?: return
            params.x = (params.x + dx).coerceIn(-100, screenWidth - 100)
            params.y = (params.y + dy).coerceIn(0, screenHeight - 100)
            toolbar?.let { windowManager.updateViewLayout(it, params) }
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
        withContext(Dispatchers.Main) { views.forEach { it.visibility = View.INVISIBLE } }
        try {
            delay(200) // let the compositor drop the hidden frame before requesting
            return ClickerAccessibilityService.captureScreen()
        } finally {
            withContext(Dispatchers.Main) { views.forEach { it.visibility = View.VISIBLE } }
        }
    }

    private fun captureTemplate() {
        if (busy) return
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
                        val name = store.saveTemplate(
                            ruleId = rule?.id ?: return@launch,
                            argb = result.argb,
                            width = result.width,
                            height = result.height,
                            region = region,
                        )
                        rule = rule?.copy(templateFile = name)
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
        if (busy) return
        busy = true
        scope.launch {
            try {
                val template = store.loadTemplate(current)
                if (template == null) {
                    setStatus("No template captured yet")
                    return@launch
                }
                when (val result = hiddenCapture()) {
                    is CaptureResult.Success -> {
                        val screen = GrayImage.fromArgb(result.argb, result.width, result.height)
                        val match = TemplateMatcher.findBest(
                            screen,
                            template,
                            (rects[RectBubbleView.Role.SEARCH] ?: return@launch)
                                .clampedTo(result.width, result.height),
                            0f,
                        )
                        setStatus(
                            match?.let {
                                "Best match ${(it.score * 100).toInt()}% at (${it.left},${it.top})" +
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
        for (bubble in bubbles.values) {
            runCatching { windowManager.removeView(bubble.view) }
        }
        bubbles.clear()
        toolbar?.let { runCatching { windowManager.removeView(it) } }
        toolbar = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

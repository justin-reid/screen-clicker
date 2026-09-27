package com.screenclicker.overlay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.screenclicker.MainActivity
import com.screenclicker.R
import com.screenclicker.accessibility.ClickerAccessibilityService
import com.screenclicker.store.ScriptStore
import com.screenclicker.store.SettingsRepo
import com.screenclicker.ui.openAccessibilitySettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Always-available floating control panel.
 *
 * Before this, using the app meant: open Screen Clicker → pick a script → Start → switch
 * back to the target app, and the same dance in reverse to stop or edit anything. Now
 * the panel floats over whatever app is in front: start and stop scripts, see what the
 * scan loop is doing, and open the rule editor for any rule without ever going to the
 * launcher.
 *
 * A foreground service because the panel must outlive the app being in the background.
 */
class ControlPanelService : Service() {

    companion object {
        private const val TAG = "ControlPanel"
        private const val CHANNEL_ID = "panel"
        private const val NOTIFICATION_ID = 43
        private const val SERVICE_POLL_MS = 2_000L

        @Volatile
        private var instance: ControlPanelService? = null

        /** Drives the settings toggle. */
        val isRunning: Boolean get() = instance != null

        fun start(context: Context) {
            context.startForegroundService(Intent(context, ControlPanelService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ControlPanelService::class.java))
        }

        /**
         * Hides the panel for the duration of a screenshot. The rule editor captures the
         * whole display minus its own windows, and this panel is a different service's
         * window — left visible, it would end up inside the captured template.
         */
        fun setMuted(muted: Boolean) {
            instance?.setMutedInternal(muted)
        }

        /**
         * Hides the panel *and* takes its window out of the touch path while the system
         * installer is on screen. Hiding alone is not enough: the panel's window has an
         * explicit size and draws above a normal app's dialog, so a merely hidden panel
         * would keep swallowing the taps meant for the installer's Update button.
         */
        fun setStandDown(standDown: Boolean) {
            instance?.setStandDownInternal(standDown)
        }
    }

    private var muted = false
    private var standDown = false

    private fun setMutedInternal(value: Boolean) {
        muted = value
        applyWindowState()
    }

    private fun setStandDownInternal(value: Boolean) {
        standDown = value
        applyWindowState()
    }

    /**
     * One place decides whether the panel may be seen and touched: muted while a capture is
     * in progress, or standing down for the system installer. The flag goes on the *window*,
     * not just the view, because a GONE view inside a fixed-size window still has a window
     * to receive touches with.
     */
    private fun applyWindowState() {
        val view = panelView ?: return
        val hidden = muted || standDown
        view.visibility = if (hidden) View.GONE else View.VISIBLE
        val params = params ?: return
        val flags = if (hidden) {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }
        if (flags == params.flags) return
        params.flags = flags
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var store: ScriptStore
    private lateinit var windowManager: WindowManager

    private var panelView: ControlPanelView? = null
    private var params: WindowManager.LayoutParams? = null

    private var scripts: List<com.screenclicker.model.Script> = emptyList()
    private var expanded = false
    private var expandedScriptId: String? = null
    private var accessibilityOn = true

    private var logicalX = 0
    private var logicalY = 0

    private val density: Float by lazy { resources.displayMetrics.density }
    private val collapsedSizePx: Int by lazy { (64 * density).toInt() }
    private val expandedWidthPx: Int by lazy { (300 * density).toInt() }
    private val statusBarInset: Int by lazy {
        windowManager.currentWindowMetrics.windowInsets
            .getInsetsIgnoringVisibility(WindowInsets.Type.systemBars())
            .top
    }
    private val screenWidth: Int by lazy { windowManager.maximumWindowMetrics.bounds.width() }
    private val screenHeight: Int by lazy { windowManager.maximumWindowMetrics.bounds.height() }
    private val expandedMaxHeightPx: Int by lazy { (screenHeight * 0.6f).toInt() }

    override fun onCreate() {
        super.onCreate()
        instance = this
        store = ScriptStore(this)
        windowManager = getSystemService(WindowManager::class.java)!!
        startInForeground()
        addPanel()

        // Run state and runner status come from the accessibility service; showing them
        // here is the whole point of not needing the app open.
        scope.launch {
            ClickerAccessibilityService.runningScriptId.collect { render() }
        }
        scope.launch {
            ClickerAccessibilityService.lastRunnerEvent.collect { render() }
        }
        scope.launch {
            while (true) {
                accessibilityOn = ClickerAccessibilityService.isRunning
                render()
                delay(SERVICE_POLL_MS)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (panelView == null) addPanel()
        refreshScripts()
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        panelView?.let { view -> runCatching { windowManager.removeView(view) } }
        panelView = null
        params = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun addPanel() {
        val view = ControlPanelView(this, callbacks)
        val layoutParams = WindowManager.LayoutParams(
            collapsedSizePx,
            collapsedSizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        // Start on the right edge a third of the way down: reachable with one thumb and
        // clear of both the status bar and the navigation gestures.
        logicalX = (screenWidth - collapsedSizePx - (12 * density).toInt()).coerceAtLeast(0)
        logicalY = statusBarInset + screenHeight / 3
        layoutParams.x = logicalX
        layoutParams.y = logicalY
        try {
            windowManager.addView(view, layoutParams)
        } catch (e: Exception) {
            // Overlay permission can be revoked while a sticky service restarts.
            Log.w(TAG, "panel unavailable", e)
            stopSelf()
            return
        }
        panelView = view
        params = layoutParams
        // A panel created while muted or standing down must start out of the way.
        applyWindowState()
        render()
    }

    private fun refreshScripts() {
        scope.launch {
            scripts = withContext(Dispatchers.IO) { store.list() }
            render()
        }
    }

    /** Expanded/collapsed state of the last resize, so size is only recomputed on change. */
    private var lastResizedExpanded = false

    private fun render() {
        val view = panelView ?: return
        val runningId = ClickerAccessibilityService.runningScriptId.value
        val expandedId = expandedScriptId
        val rules = expandedId
            ?.let { id -> scripts.firstOrNull { it.id == id } }
            ?.rules
            ?.map { rule ->
                ControlPanelView.RuleRow(
                    id = rule.id,
                    name = rule.name,
                    hasTemplate = rule.hasTemplate,
                )
            }
            .orEmpty()

        val structureChanged = view.render(
            ControlPanelView.State(
                expanded = expanded,
                scripts = scripts.map { script ->
                    ControlPanelView.ScriptRow(
                        id = script.id,
                        name = script.name,
                        enabledRuleCount = script.rules.count { it.enabled },
                    )
                },
                runningScriptId = runningId,
                expandedScriptId = expandedId,
                rules = rules,
                lastEvent = ClickerAccessibilityService.lastRunnerEvent.value,
                accessibilityOn = accessibilityOn,
            ),
        )
        // Runner events arrive several times a second; only re-measure when something
        // structural (or the expanded state) actually changed.
        if (structureChanged || expanded != lastResizedExpanded) {
            lastResizedExpanded = expanded
            resizeAndPosition()
        }
    }

    /** Panel grows when expanded; bubble stays a thumb-sized dot. */
    private fun resizeAndPosition() {
        val layoutParams = params ?: return
        val view = panelView ?: return
        if (expanded) {
            layoutParams.width = expandedWidthPx
            view.measure(
                View.MeasureSpec.makeMeasureSpec(expandedWidthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(expandedMaxHeightPx, View.MeasureSpec.AT_MOST),
            )
            layoutParams.height = view.measuredHeight.coerceAtMost(expandedMaxHeightPx)
        } else {
            layoutParams.width = collapsedSizePx
            layoutParams.height = collapsedSizePx
        }
        logicalX = logicalX.coerceIn(0, (screenWidth - layoutParams.width).coerceAtLeast(0))
        logicalY = logicalY.coerceIn(
            statusBarInset,
            (screenHeight - layoutParams.height).coerceAtLeast(statusBarInset),
        )
        layoutParams.x = logicalX
        layoutParams.y = logicalY
        runCatching { windowManager.updateViewLayout(view, layoutParams) }
    }

    private val callbacks = object : ControlPanelView.Callbacks {
        override fun onToggleExpanded() {
            expanded = !expanded
            if (!expanded) expandedScriptId = null
            if (expanded) refreshScripts() else render()
        }

        override fun onMove(dx: Int, dy: Int) {
            logicalX += dx
            logicalY += dy
            resizeAndPosition()
        }

        override fun onToggleScript(scriptId: String) {
            if (ClickerAccessibilityService.runningScriptId.value == scriptId) {
                ClickerAccessibilityService.stopScript()
                render()
                return
            }
            if (!ClickerAccessibilityService.startScript(this@ControlPanelService, scriptId)) {
                Toast.makeText(
                    this@ControlPanelService,
                    getString(R.string.panel_needs_accessibility),
                    Toast.LENGTH_LONG,
                ).show()
                accessibilityOn = false
            }
            render()
        }

        override fun onExpandScript(scriptId: String) {
            expandedScriptId = if (expandedScriptId == scriptId) null else scriptId
            render()
        }

        override fun onEditRule(scriptId: String, ruleId: String) {
            expanded = false
            expandedScriptId = null
            render()
            if (!Settings.canDrawOverlays(this@ControlPanelService)) {
                runCatching {
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName"),
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
                Toast.makeText(
                    this@ControlPanelService,
                    getString(R.string.panel_needs_overlay_permission),
                    Toast.LENGTH_LONG,
                ).show()
                return
            }
            ConfigOverlayService.start(this@ControlPanelService, ruleId)
        }

        override fun onOpenApp() {
            expanded = false
            render()
            runCatching {
                startActivity(
                    Intent(this@ControlPanelService, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }

        override fun onHide() {
            // Keep the saved preference in step, or the app would restart the panel on
            // the next launch after the user deliberately dismissed it.
            val repo = SettingsRepo(this@ControlPanelService)
            repo.save(repo.load().copy(controlPanelEnabled = false))
            stopSelf()
        }

        override fun onEnableAccess() {
            runCatching { openAccessibilitySettings() }
        }
    }

    private fun startInForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_panel),
                NotificationManager.IMPORTANCE_MIN,
            ).apply { description = getString(R.string.channel_panel_description) },
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.panel_notification_text))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
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
        Log.i(TAG, "control panel started")
    }
}

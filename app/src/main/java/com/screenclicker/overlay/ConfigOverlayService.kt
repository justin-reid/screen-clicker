package com.screenclicker.overlay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.getSystemService
import com.screenclicker.R
import com.screenclicker.accessibility.ClickerAccessibilityService
import com.screenclicker.capture.CaptureResult
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
 * Full-screen overlay for configuring one rule over the live app: drag the search and
 * click rectangles into place, capture the template from the screen itself, and check
 * the match live. Plain View + WindowManager (see AGENTS.md), foreground service of
 * type specialUse so it survives the app going to the background mid-edit.
 *
 * Template capture subtlety: takeScreenshot captures EVERYTHING on screen, including
 * our own rectangles. So the editor is hidden for the duration of the capture and
 * restored right after — the crop then contains only the target app.
 */
class ConfigOverlayService : Service() {

    companion object {
        private const val TAG = "ConfigOverlay"
        private const val CHANNEL_ID = "overlay"
        private const val NOTIFICATION_ID = 41

        fun start(context: Context, ruleId: String) {
            val intent = Intent(context, ConfigOverlayService::class.java)
                .putExtra(EXTRA_RULE_ID, ruleId)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ConfigOverlayService::class.java))
        }

        /** Emits the saved Rule whenever the overlay Save button succeeds. */
        val savedRules = MutableStateFlow<Rule?>(null)

        const val EXTRA_RULE_ID = "ruleId"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var store: ScriptStore
    private lateinit var windowManager: WindowManager

    private var rule: Rule? = null
    private var ruleScriptId: String? = null
    private var editorView: RectEditorView? = null
    private var statusText: android.widget.TextView? = null
    private var searchRect = PxRect(0, 0, 0, 0)
    private var clickRect = PxRect(0, 0, 0, 0)
    private var templateRect = PxRect(0, 0, 0, 0)
    private var busy = false

    override fun onCreate() {
        super.onCreate()
        store = ScriptStore(this)
        windowManager = getSystemService(WindowManager::class.java)!!
        startInForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val ruleId = intent?.getStringExtra(EXTRA_RULE_ID)
        val loaded = ruleId?.let { id ->
            store.list().firstOrNull { script -> script.rules.any { it.id == id } }
        }
        if (loaded == null) {
            Log.w(TAG, "Rule $ruleId not found; closing overlay")
            stopSelf()
            return START_NOT_STICKY
        }
        val script = loaded
        ruleScriptId = script.id
        rule = script.rules.first { it.id == ruleId }
        if (editorView == null) setUpWindows()
        return START_NOT_STICKY
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
        if (android.os.Build.VERSION.SDK_INT >= 34) {
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

    private fun setUpWindows() {
        val current = rule ?: return
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels

        // Rule rects may come from a different resolution (import, newer device);
        // clamp before the view sees them or the drag clamp will throw.
        searchRect = if (current.searchRegion.width > 0) {
            current.searchRegion.clampedTo(screenW, screenH)
        } else {
            RectEditorView.defaultRect(0.5f, screenW, screenH)
        }
        clickRect = if ((current.clickRegion?.width ?: 0) > 0) {
            current.clickRegion!!.clampedTo(screenW, screenH)
        } else {
            RectEditorView.defaultRect(0.2f, screenW, screenH)
        }
        templateRect = RectEditorView.defaultRect(0.25f, screenW, screenH)

        val root = LayoutInflater.from(this).inflate(R.layout.overlay_config, null)
        editorView = root.findViewById<RectEditorView>(R.id.editor)?.apply {
            boxes = listOf(
                RectEditorView.Box(
                    RectEditorView.Role.SEARCH,
                    searchRect,
                    getString(R.string.overlay_role_search),
                ),
                RectEditorView.Box(
                    RectEditorView.Role.TEMPLATE,
                    templateRect,
                    getString(R.string.overlay_role_template),
                ),
                RectEditorView.Box(
                    RectEditorView.Role.CLICK,
                    clickRect,
                    getString(R.string.overlay_role_click),
                ),
            )
            activeRole = RectEditorView.Role.SEARCH
            onRectChanged = { role, rect ->
                when (role) {
                    RectEditorView.Role.SEARCH -> searchRect = rect
                    RectEditorView.Role.TEMPLATE -> templateRect = rect
                    RectEditorView.Role.CLICK -> clickRect = rect
                }
            }
        }
        statusText = root.findViewById(R.id.status)

        root.findViewById<android.widget.Button>(R.id.roleSearch).setOnClickListener {
            editorView?.activeRole = RectEditorView.Role.SEARCH
        }
        root.findViewById<android.widget.Button>(R.id.roleTemplate).setOnClickListener {
            editorView?.activeRole = RectEditorView.Role.TEMPLATE
        }
        root.findViewById<android.widget.Button>(R.id.roleClick).setOnClickListener {
            editorView?.activeRole = RectEditorView.Role.CLICK
        }
        root.findViewById<android.widget.Button>(R.id.actionCapture).setOnClickListener { captureTemplate() }
        root.findViewById<android.widget.Button>(R.id.actionCheck).setOnClickListener { checkMatch() }
        root.findViewById<android.widget.Button>(R.id.actionSave).setOnClickListener { saveAndClose() }
        root.findViewById<android.widget.Button>(R.id.actionCancel).setOnClickListener { stopSelf() }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
        windowManager.addView(root, params)
    }

    private fun setStatus(text: String) {
        statusText?.text = text
    }

    private fun editorRoot(): android.view.View? = editorView?.parent as? android.view.View

    /**
     * Capture while the editor is hidden, so our own rectangles never appear in the
     * pixels. The capture itself may sit out the system's rate-limit window with the
     * editor hidden — up to ~1.1s — which reads as a brief blink.
     */
    private suspend fun hiddenCapture(): CaptureResult? {
        val root = editorRoot() ?: return null
        withContext(Dispatchers.Main) { root.visibility = android.view.View.INVISIBLE }
        try {
            delay(200) // let the compositor drop the hidden frame before requesting
            return ClickerAccessibilityService.captureScreen()
        } finally {
            withContext(Dispatchers.Main) { root.visibility = android.view.View.VISIBLE }
        }
    }

    private fun captureTemplate() {
        if (busy) return
        busy = true
        scope.launch {
            try {
                when (val result = hiddenCapture()) {
                    is CaptureResult.Success -> {
                        val region = templateRect.clampedTo(result.width, result.height)
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
                            searchRect.clampedTo(result.width, result.height),
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
        var updated = current.copy(searchRegion = searchRect)
        if (updated.clickMode == com.screenclicker.model.ClickMode.ON_REGION) {
            updated = updated.copy(clickRegion = clickRect)
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

    override fun onDestroy() {
        editorView?.let { view ->
            (view.parent as? android.view.View)?.let { windowManager.removeView(it) }
        }
        editorView = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

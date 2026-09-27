package com.screenclicker.overlay

import android.content.Context
import android.graphics.Color
import android.text.TextUtils
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs

/**
 * The floating control panel: a small bubble that expands into a script picker.
 *
 * This exists so the app itself never has to be opened during normal use. Starting a
 * script, stopping it, seeing whether it found anything, and jumping into the rule
 * editor for any rule all happen here, on top of whatever app is being automated —
 * no switching back and forth to the launcher.
 *
 * Views, not Compose: it lives in a window over other apps, like the editor overlays.
 */
class ControlPanelView(
    context: Context,
    private val callbacks: Callbacks,
) : LinearLayout(context) {

    interface Callbacks {
        fun onToggleExpanded()
        fun onMove(dx: Int, dy: Int)
        fun onToggleScript(scriptId: String)
        fun onExpandScript(scriptId: String)
        fun onEditRule(scriptId: String, ruleId: String)
        fun onOpenApp()
        fun onHide()
        fun onEnableAccess()
    }

    data class ScriptRow(val id: String, val name: String, val enabledRuleCount: Int)

    data class RuleRow(val id: String, val name: String, val hasTemplate: Boolean)

    data class State(
        val expanded: Boolean = false,
        val scripts: List<ScriptRow> = emptyList(),
        val runningScriptId: String? = null,
        val expandedScriptId: String? = null,
        val rules: List<RuleRow> = emptyList(),
        val lastEvent: String? = null,
        val accessibilityOn: Boolean = true,
    )

    private val density = resources.displayMetrics.density

    private val bubble = TextView(context).apply {
        text = "\u25B6"
        setTextColor(Color.WHITE)
        textSize = 22f
        gravity = Gravity.CENTER
        background = rounded(0xFF2E4A5A.toInt(), 0x66FFFFFF)
    }

    private val statusText = TextView(context).apply {
        setTextColor(0xFFB0BEC5.toInt())
        textSize = 11f
        // The runner reports several lines of diagnostics here (frame size, per-rule region,
        // template and alignment, best score). Three lines silently ate exactly the numbers
        // that are needed to explain a detection failure.
        maxLines = 8
        ellipsize = TextUtils.TruncateAt.END
    }
    private val title = TextView(context).apply {
        text = context.getString(com.screenclicker.R.string.panel_title)
        setTextColor(Color.WHITE)
        textSize = 13f
    }
    private val content = LinearLayout(context).apply { orientation = VERTICAL }
    private val panel = ScrollView(context).apply {
        addView(content)
        visibility = GONE
    }

    init {
        orientation = VERTICAL
        background = rounded(0xE6121720.toInt(), 0x55FFFFFF)
        setPadding(dp(6), dp(6), dp(6), dp(6))

        // Bubble: tap expands, drag moves. Total movement decides which it was.
        var lastRawX = 0f
        var lastRawY = 0f
        var moved = 0f
        var accX = 0f
        var accY = 0f
        bubble.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                    moved = 0f
                    accX = 0f
                    accY = 0f
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    // Sub-pixel accumulator: raw deltas are floats but the window takes
                    // ints, and truncating each event would stall a slow drag.
                    accX += event.rawX - lastRawX
                    accY += event.rawY - lastRawY
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                    val dx = accX.toInt()
                    val dy = accY.toInt()
                    if (dx != 0 || dy != 0) {
                        accX -= dx
                        accY -= dy
                        moved += abs(dx) + abs(dy)
                        callbacks.onMove(dx, dy)
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (moved < dp(8)) callbacks.onToggleExpanded()
                    true
                }

                else -> false
            }
        }
        addView(bubble, LayoutParams(dp(52), dp(52)))

        // Panel header doubles as the drag handle.
        val header = LinearLayout(context).apply { orientation = HORIZONTAL }
        header.addView(title, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        val close = textButton("\u2715") { callbacks.onToggleExpanded() }
        close.setTextColor(0xFFEF9A9A.toInt())
        header.addView(close)
        var headerLastX = 0f
        var headerLastY = 0f
        var headerAccX = 0f
        var headerAccY = 0f
        header.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    headerLastX = event.rawX
                    headerLastY = event.rawY
                    headerAccX = 0f
                    headerAccY = 0f
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    // Same sub-pixel accumulator as the bubble: truncating each event
                    // would stall a slow drag entirely.
                    headerAccX += event.rawX - headerLastX
                    headerAccY += event.rawY - headerLastY
                    headerLastX = event.rawX
                    headerLastY = event.rawY
                    val dx = headerAccX.toInt()
                    val dy = headerAccY.toInt()
                    if (dx != 0 || dy != 0) {
                        headerAccX -= dx
                        headerAccY -= dy
                        callbacks.onMove(dx, dy)
                    }
                    true
                }

                else -> false
            }
        }

        content.addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        content.addView(statusText, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(panel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        render(State())
    }

    /**
     * Renders [state] and reports whether the list had to be rebuilt.
     *
     * The status line and the bubble glyph update on every call (runner events arrive
     * several times a second), but the row views are only rebuilt when something
     * structural changed: replacing a button under the user's finger drops the tap.
     */
    fun render(state: State): Boolean {
        bubble.visibility = if (state.expanded) GONE else VISIBLE
        panel.visibility = if (state.expanded) VISIBLE else GONE
        // Set before the early return: the collapsed bubble is the state indicator.
        bubble.text = if (state.runningScriptId == null) "\u25B6" else "\u25A0"

        val runningName = state.scripts.firstOrNull { it.id == state.runningScriptId }?.name
        statusText.text = buildString {
            append(
                when {
                    !state.accessibilityOn ->
                        context.getString(com.screenclicker.R.string.panel_accessibility_off)
                    runningName != null -> "Running: $runningName"
                    else -> "Idle"
                },
            )
            state.lastEvent?.let { append("\n$it") }
        }

        if (!state.expanded) return false

        val structural = listOf(
            state.scripts,
            state.runningScriptId,
            state.expandedScriptId,
            state.rules,
            state.accessibilityOn,
        )
        if (structural == structuralKey) return false
        structuralKey = structural

        // Rebuild the list; it is short and this keeps state handling trivial.
        while (content.childCount > 2) content.removeViewAt(2)

        if (state.scripts.isEmpty()) {
            content.addView(
                note("No scripts yet — open the app to create one."),
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
            )
        }
        for (script in state.scripts) {
            content.addView(scriptRow(script, state), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            if (state.expandedScriptId == script.id) {
                for (rule in state.rules) {
                    content.addView(
                        ruleRow(script.id, rule),
                        LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
                    )
                }
                if (state.rules.isEmpty()) content.addView(note("No rules in this script."), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            }
        }

        if (!state.accessibilityOn) {
            val enable = textButton(context.getString(com.screenclicker.R.string.overlay_enable_access)) {
                callbacks.onEnableAccess()
            }
            enable.setTextColor(0xFFFFAB91.toInt())
            content.addView(enable, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }

        val footer = LinearLayout(context).apply { orientation = HORIZONTAL }
        footer.addView(textButton(context.getString(com.screenclicker.R.string.panel_open_app)) {
            callbacks.onOpenApp()
        })
        footer.addView(textButton(context.getString(com.screenclicker.R.string.panel_hide)) {
            callbacks.onHide()
        })
        content.addView(footer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        return true
    }

    /** Contents of the last structural render, to avoid rebuilding identical rows. */
    private var structuralKey: List<Any?>? = null

    private fun scriptRow(script: ScriptRow, state: State): View {
        val row = LinearLayout(context).apply { orientation = HORIZONTAL }
        val running = state.runningScriptId == script.id
        val name = TextView(context).apply {
            text = script.name
            setTextColor(if (running) 0xFFA5D6A7.toInt() else Color.WHITE)
            textSize = 13f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(name, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        val run = textButton(if (running) "\u25A0" else "\u25B6") {
            callbacks.onToggleScript(script.id)
        }
        run.setTextColor(if (running) 0xFFEF9A9A.toInt() else 0xFFA5D6A7.toInt())
        row.addView(run)

        val rules = textButton(context.getString(com.screenclicker.R.string.panel_rules)) {
            callbacks.onExpandScript(script.id)
        }
        rules.alpha = if (state.expandedScriptId == script.id) 1f else 0.6f
        row.addView(rules)
        return row
    }

    private fun ruleRow(scriptId: String, rule: RuleRow): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setPadding(dp(12), 0, 0, 0)
        }
        val label = TextView(context).apply {
            text = if (rule.hasTemplate) rule.name else "${rule.name} (no image)"
            setTextColor(0xFFCFD8DC.toInt())
            textSize = 12f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(label, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        row.addView(textButton(context.getString(com.screenclicker.R.string.panel_edit)) {
            callbacks.onEditRule(scriptId, rule.id)
        })
        return row
    }

    private fun note(message: String): TextView = TextView(context).apply {
        text = message
        setTextColor(0xFF90A4AE.toInt())
        textSize = 11f
        setPadding(dp(4), dp(2), dp(4), dp(2))
    }

    private fun textButton(label: String, onClick: () -> Unit): Button =
        Button(context, null, android.R.attr.buttonBarButtonStyle).apply {
            text = label
            setTextColor(Color.WHITE)
            setPadding(dp(8), dp(2), dp(8), dp(2))
            minimumWidth = 0
            minWidth = 0
            minimumHeight = dp(44)
            minHeight = dp(44)
            setOnClickListener { onClick() }
        }

    private fun rounded(fill: Int, stroke: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(14).toFloat()
        setColor(fill)
        setStroke(dp(1), stroke)
    }

    private fun dp(value: Int): Int = (value * density).toInt()
}

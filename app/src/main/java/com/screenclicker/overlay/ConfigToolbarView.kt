package com.screenclicker.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Draggable floating toolbar for the rule editor. Small window, so the rest of the
 * screen (including app switching) is untouched while configuring.
 */
class ConfigToolbarView(
    context: Context,
    private val callbacks: Callbacks,
) : LinearLayout(context) {

    interface Callbacks {
        fun onRoleSelected(role: RectBubbleView.Role)
        fun onCaptureTemplate()
        fun onCheckMatch()
        fun onSwitchApp()
        fun onSave()
        fun onCancel()
        fun onMove(dx: Int, dy: Int)
    }

    private val statusView: TextView
    private val roleButtons = mutableMapOf<RectBubbleView.Role, Button>()

    init {
        orientation = VERTICAL
        setBackgroundColor(0xE6101018.toInt())
        setPadding(dp(8), dp(6), dp(8), dp(6))

        val header = TextView(context).apply {
            text = context.getString(com.screenclicker.R.string.overlay_editor_title)
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(2), dp(4), dp(2))
            // Visible grip: rounded outline so the drag area is discoverable.
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), 0x55FFFFFF)
            }
        }
        var dragLast = 0f to 0f
        header.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragLast = event.rawX to event.rawY
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    callbacks.onMove(
                        (event.rawX - dragLast.first).toInt(),
                        (event.rawY - dragLast.second).toInt(),
                    )
                    dragLast = event.rawX to event.rawY
                    true
                }

                else -> false
            }
        }
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        val roleRow = LinearLayout(context).apply { orientation = HORIZONTAL }
        fun roleButton(role: RectBubbleView.Role, labelRes: Int, color: Int): Button {
            val button = Button(context, null, android.R.attr.buttonBarButtonStyle)
            button.text = context.getString(labelRes)
            button.setTextColor(color)
            button.setPadding(dp(6), dp(2), dp(6), dp(2))
            button.minimumWidth = 0
            button.minWidth = 0
            button.setOnClickListener { callbacks.onRoleSelected(role) }
            roleButtons[role] = button
            roleRow.addView(button)
            return button
        }
        roleButton(
            RectBubbleView.Role.SEARCH,
            com.screenclicker.R.string.overlay_role_search,
            0xFF80CBC4.toInt(),
        )
        roleButton(
            RectBubbleView.Role.TEMPLATE,
            com.screenclicker.R.string.overlay_role_template,
            0xFFFFD54F.toInt(),
        )
        roleButton(
            RectBubbleView.Role.CLICK,
            com.screenclicker.R.string.overlay_role_click,
            0xFF90CAF9.toInt(),
        )
        addView(roleRow, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        val actionRow = LinearLayout(context).apply { orientation = HORIZONTAL }
        fun actionButton(labelRes: Int, onClick: () -> Unit): Button {
            val button = Button(context, null, android.R.attr.buttonBarButtonStyle)
            button.text = context.getString(labelRes)
            button.setPadding(dp(6), dp(2), dp(6), dp(2))
            button.minimumWidth = 0
            button.minWidth = 0
            button.setOnClickListener { onClick() }
            actionRow.addView(button)
            return button
        }
        actionButton(com.screenclicker.R.string.overlay_capture) { callbacks.onCaptureTemplate() }
        actionButton(com.screenclicker.R.string.overlay_check) { callbacks.onCheckMatch() }
        actionButton(com.screenclicker.R.string.overlay_recents) { callbacks.onSwitchApp() }
        addView(actionRow, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        val commitRow = LinearLayout(context).apply { orientation = HORIZONTAL }
        val save = actionButton(com.screenclicker.R.string.overlay_save) { callbacks.onSave() }
        save.setTextColor(0xFFA5D6A7.toInt())
        val cancel = actionButton(com.screenclicker.R.string.overlay_cancel) { callbacks.onCancel() }
        cancel.setTextColor(0xFFEF9A9A.toInt())
        addView(commitRow, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        statusView = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 11f
            maxLines = 3
            setPadding(dp(4), dp(2), dp(4), dp(2))
        }
        addView(statusView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun setStatus(text: String) {
        statusView.text = text
    }

    fun setActiveRole(role: RectBubbleView.Role) {
        for ((candidate, button) in roleButtons) {
            button.alpha = if (candidate == role) 1f else 0.45f
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

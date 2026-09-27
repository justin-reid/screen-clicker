package com.screenclicker.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Draggable floating toolbar for the rule editor. Small window, so the rest of the
 * screen (including app switching) is untouched while configuring.
 *
 * Also owns the service-state alert strip: the accessibility service is what performs
 * both the screenshot and the taps, and Android switches it off on every reinstall —
 * so "why did capture fail" needs to be visible right here, not buried in settings.
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
        fun onEnableAccess()
    }

    private val statusView = TextView(context)

    private val roleButtons = mutableMapOf<RectBubbleView.Role, Button>()
    private val captureButtons = mutableListOf<Button>()

    private val alertRow = LinearLayout(context).apply {
        orientation = HORIZONTAL
        visibility = GONE
    }
    private val alertText = TextView(context).apply {
        setTextColor(0xFFFFAB91.toInt())
        textSize = 11f
        maxLines = 4
        setPadding(dp(4), dp(2), dp(4), dp(2))
    }
    private val enableButton = Button(context, null, android.R.attr.buttonBarButtonStyle)

    /**
     * Last captured/imported template, shown right where the user captured it: this is
     * the only way to see immediately whether the crop really matches the box on screen.
     */
    private val previewImage = ImageView(context)
    private val previewLabel = TextView(context).apply {
        setTextColor(0xFFCCCCCC.toInt())
        textSize = 10f
        setPadding(dp(4), 0, dp(4), dp(2))
    }
    private val previewRow = LinearLayout(context).apply {
        orientation = VERTICAL
        visibility = GONE
    }

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
        var dragLastX = 0f
        var dragLastY = 0f
        // Sub-pixel remainder: raw deltas are floats but the window takes ints, so
        // truncating every event would stall a slow drag completely.
        var dragAccX = 0f
        var dragAccY = 0f
        header.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragLastX = event.rawX
                    dragLastY = event.rawY
                    dragAccX = 0f
                    dragAccY = 0f
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    dragAccX += event.rawX - dragLastX
                    dragAccY += event.rawY - dragLastY
                    dragLastX = event.rawX
                    dragLastY = event.rawY
                    val dx = dragAccX.toInt()
                    val dy = dragAccY.toInt()
                    if (dx != 0 || dy != 0) {
                        dragAccX -= dx
                        dragAccY -= dy
                        callbacks.onMove(dx, dy)
                    }
                    true
                }

                else -> false
            }
        }
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        val roleRow = LinearLayout(context).apply { orientation = HORIZONTAL }
        roleButton(
            roleRow,
            RectBubbleView.Role.SEARCH,
            com.screenclicker.R.string.overlay_role_search,
            0xFF80CBC4.toInt(),
        )
        roleButton(
            roleRow,
            RectBubbleView.Role.TEMPLATE,
            com.screenclicker.R.string.overlay_role_template,
            0xFFFFD54F.toInt(),
        )
        roleButton(
            roleRow,
            RectBubbleView.Role.CLICK,
            com.screenclicker.R.string.overlay_role_click,
            0xFF90CAF9.toInt(),
        )
        addView(roleRow, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        val actionRow = LinearLayout(context).apply { orientation = HORIZONTAL }
        captureButtons += actionButton(actionRow, com.screenclicker.R.string.overlay_capture) {
            callbacks.onCaptureTemplate()
        }
        captureButtons += actionButton(actionRow, com.screenclicker.R.string.overlay_check) {
            callbacks.onCheckMatch()
        }
        actionButton(actionRow, com.screenclicker.R.string.overlay_recents) { callbacks.onSwitchApp() }
        addView(actionRow, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        previewImage.setPadding(dp(4), dp(4), dp(4), 0)
        previewRow.addView(previewImage)
        previewRow.addView(previewLabel)
        addView(previewRow, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        // Only surfaces when the accessibility service is off or still connecting.
        alertRow.addView(alertText)
        enableButton.text = context.getString(com.screenclicker.R.string.overlay_enable_access)
        enableButton.setTextColor(0xFFFFAB91.toInt())
        enableButton.setPadding(dp(6), dp(2), dp(6), dp(2))
        enableButton.minimumWidth = 0
        enableButton.minWidth = 0
        enableButton.visibility = GONE
        enableButton.setOnClickListener { callbacks.onEnableAccess() }
        alertRow.addView(enableButton)
        addView(alertRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        val commitRow = LinearLayout(context).apply { orientation = HORIZONTAL }
        val save = actionButton(commitRow, com.screenclicker.R.string.overlay_save) { callbacks.onSave() }
        save.setTextColor(0xFFA5D6A7.toInt())
        val cancel =
            actionButton(commitRow, com.screenclicker.R.string.overlay_cancel) { callbacks.onCancel() }
        cancel.setTextColor(0xFFEF9A9A.toInt())
        addView(commitRow, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        statusView.apply {
            setTextColor(Color.WHITE)
            textSize = 11f
            maxLines = 3
            setPadding(dp(4), dp(2), dp(4), dp(2))
        }
        addView(statusView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    private fun roleButton(
        row: LinearLayout,
        role: RectBubbleView.Role,
        labelRes: Int,
        color: Int,
    ) {
        val button = Button(context, null, android.R.attr.buttonBarButtonStyle)
        button.text = context.getString(labelRes)
        button.setTextColor(color)
        button.setPadding(dp(6), dp(2), dp(6), dp(2))
        button.minimumWidth = 0
        button.minWidth = 0
        button.minimumHeight = dp(44)
        button.minHeight = dp(44)
        button.setOnClickListener { callbacks.onRoleSelected(role) }
        roleButtons[role] = button
        row.addView(button)
    }

    private fun actionButton(row: LinearLayout, labelRes: Int, onClick: () -> Unit): Button {
        val button = Button(context, null, android.R.attr.buttonBarButtonStyle)
        button.text = context.getString(labelRes)
        button.setPadding(dp(6), dp(2), dp(6), dp(2))
        button.minimumWidth = 0
        button.minWidth = 0
        // Finger-sized targets: the toolbar sits over live UI the user is also using.
        button.minimumHeight = dp(44)
        button.minHeight = dp(44)
        button.setOnClickListener { onClick() }
        row.addView(button)
        return button
    }

    fun setStatus(text: String) {
        statusView.text = text
    }

    /** Shows the crop that was just captured, with a one-line description. */
    fun setTemplatePreview(bitmap: Bitmap, label: String) {
        previewImage.setImageBitmap(scaleToFit(bitmap))
        previewLabel.text = label
        previewRow.visibility = VISIBLE
    }

    fun clearTemplatePreview() {
        previewImage.setImageDrawable(null)
        previewRow.visibility = GONE
    }

    /** Keeps tall templates from eating the toolbar; keeps wide ones from running off. */
    private fun scaleToFit(bitmap: Bitmap): Bitmap {
        val maxWidth = dp(240)
        val maxHeight = dp(80)
        if (bitmap.width <= maxWidth && bitmap.height <= maxHeight) return bitmap
        val scale = minOf(
            maxWidth.toFloat() / bitmap.width,
            maxHeight.toFloat() / bitmap.height,
        )
        val width = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val height = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    /**
     * Persistent warning strip ("service is off") with an optional jump to the system
     * accessibility settings. Pass null to clear.
     */
    fun setAlert(message: String?, showEnableAction: Boolean = false) {
        if (message == null) {
            alertRow.visibility = GONE
            return
        }
        alertText.text = message
        enableButton.visibility = if (showEnableAction) VISIBLE else GONE
        alertRow.visibility = VISIBLE
    }

    /** Capture/Check are meaningless without the accessibility connection. */
    fun setCaptureEnabled(enabled: Boolean) {
        for (button in captureButtons) {
            button.isEnabled = enabled
            button.alpha = if (enabled) 1f else 0.4f
        }
    }

    /**
     * Highlights the area currently on screen. Null = none of them: the editor starts with
     * no rectangle at all, and tapping the highlighted one hides it again.
     */
    fun setActiveRole(role: RectBubbleView.Role?) {
        for ((candidate, button) in roleButtons) {
            button.alpha = if (candidate == role) 1f else 0.45f
        }
        if (role == null) setStatus(context.getString(com.screenclicker.R.string.overlay_no_area))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

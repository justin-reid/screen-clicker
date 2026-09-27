package com.screenclicker.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import com.screenclicker.vision.MatchResult

/**
 * Non-touchable transparent layer that highlights what the runner just matched, with
 * the match score. Owned by [com.screenclicker.accessibility.ClickerAccessibilityService]
 * (added while a script with "show detections" runs), because the overlay's lifetime is
 * exactly the runner's — no separate service or notification needed.
 */
class DetectionHighlightView(context: Context) : View(context) {

    @Volatile
    private var matches: List<MatchResult> = emptyList()

    private val density = resources.displayMetrics.density

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        color = Color.rgb(0, 230, 200)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x3000E6C8
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0, 230, 200)
        textSize = 13f * density
    }

    /** Thread-safe: the runner calls this from a coroutine. */
    fun update(newMatches: List<MatchResult>) {
        matches = newMatches
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val current = matches
        for (match in current) {
            val left = match.left.toFloat()
            val top = match.top.toFloat()
            val right = (match.left + match.width).toFloat()
            val bottom = (match.top + match.height).toFloat()
            canvas.drawRect(left, top, right, bottom, fill)
            canvas.drawRect(left, top, right, bottom, stroke)
            canvas.drawText(
                "${(match.score * 100).toInt()}%",
                left + 4f * density,
                maxOf(14f * density, top - 6f * density),
                text,
            )
        }
    }
}

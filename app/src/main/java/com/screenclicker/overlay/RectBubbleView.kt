package com.screenclicker.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * One rectangle's floating editor window. The window hugs the rect (plus a handle
 * margin) and ONLY that area — every other pixel of the screen passes through to
 * whatever app is below, so the user can switch apps while configuring.
 *
 * The window's local rect is constant at (margin, margin); the service owns the model
 * rect, applies drag deltas (this view reports deltas, not positions — the window moves
 * in lockstep with the rect, so deltas stay valid mid-drag), resizes/repositions the
 * window, and calls back with the new size for painting.
 *
 * In [probeOnly] mode it paints just the alignment probe and none of the rectangle, because
 * the rectangle's own outline, fill and label must never appear in a capture.
 */
class RectBubbleView(
    context: Context,
    val role: Role,
    private val marginPx: Int,
) : View(context) {

    enum class Role { SEARCH, TEMPLATE, CLICK }

    enum class Mode { NONE, MOVE, N, S, E, W, NW, NE, SW, SE }

    interface Callbacks {
        fun onDragDelta(role: Role, mode: Mode, dx: Int, dy: Int)
        fun onDragEnded(role: Role)
    }

    var callbacks: Callbacks? = null

    /** Active = bright + handles + interactive; set by the service on role switches. */
    var active: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    /** Current rect size in px (service model); the local rect origin is constant. */
    var rectWidth = 0
        private set
    var rectHeight = 0
        private set

    fun setSize(width: Int, height: Int) {
        rectWidth = width
        rectHeight = height
        invalidate()
    }

    var label: String = ""

    private val density = resources.displayMetrics.density

    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        color = Color.WHITE
    }
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = 0x88FFFFFF.toInt()
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x22FFFFFF
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 13f * density
    }
    private val handleRadius = 9f * density

    /**
     * Alignment probe: drawn instead of the rectangle while a screenshot is taken.
     *
     * The rect must not appear in the pixels being captured, but the probe marker has to —
     * so during a capture the view paints only the marker, which sits in the handle margin
     * (outside the cropped area). See [ProbeMarker].
     */
    private val probePaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = false
    }

    /** True only for the instant of a capture; the service toggles it. */
    var probeOnly: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    /**
     * Marker position inside this window, in window coordinates, computed by the service via
     * [ProbeMarker.offsetInWindow] so the painted position and the position the service
     * expects to find are the same number.
     */
    var probeOffset: Pair<Int, Int>? = null
        set(value) {
            field = value
            invalidate()
        }

    private var mode = Mode.NONE

    /** Raw screen coordinates — immune to the window moving under the finger mid-drag. */
    private var lastRawX = 0f
    private var lastRawY = 0f

    /** Sub-pixel remainder so truncation to int deltas can never stall a slow drag. */
    private var accX = 0f
    private var accY = 0f

    override fun onDraw(canvas: Canvas) {
        if (probeOnly) {
            // Null when the rectangle leaves no visible margin to hide a marker in.
            val offset = probeOffset ?: return
            ProbeMarker.draw(canvas, offset.first, offset.second, probePaint)
            return
        }
        val left = marginPx.toFloat()
        val top = marginPx.toFloat()
        val right = (marginPx + rectWidth).toFloat()
        val bottom = (marginPx + rectHeight).toFloat()
        val paint = if (active) activePaint else dimPaint
        canvas.drawRect(left, top, right, bottom, paint)
        if (!active) return

        canvas.drawRect(left, top, right, bottom, fillPaint)
        for ((hx, hy) in handleCenters(left, top, right, bottom)) {
            canvas.drawCircle(hx, hy, handleRadius, handlePaint)
        }
        canvas.drawText(
            label,
            left + 4f * density,
            maxOf(14f * density, top - 6f * density),
            labelPaint,
        )
    }

    private fun handleCenters(l: Float, t: Float, r: Float, b: Float): List<Pair<Float, Float>> =
        listOf(
            l to t, ((l + r) / 2f) to t, r to t, r to ((t + b) / 2f),
            r to b, ((l + r) / 2f) to b, l to b, l to ((t + b) / 2f),
        )

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!active) return false
        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mode = hitTest(x, y)
                lastRawX = event.rawX
                lastRawY = event.rawY
                accX = 0f
                accY = 0f
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (mode == Mode.NONE) return true
                // Deltas come from raw screen coordinates: the window shifts in lockstep
                // with the rect, so window-relative deltas would self-cancel and the
                // rect would jitter instead of following the finger.
                accX += event.rawX - lastRawX
                accY += event.rawY - lastRawY
                lastRawX = event.rawX
                lastRawY = event.rawY
                val dx = accX.toInt()
                val dy = accY.toInt()
                if (dx != 0 || dy != 0) {
                    accX -= dx
                    accY -= dy
                    callbacks?.onDragDelta(role, mode, dx, dy)
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                mode = Mode.NONE
                callbacks?.onDragEnded(role)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun hitTest(x: Float, y: Float): Mode {
        val l = marginPx.toFloat()
        val t = marginPx.toFloat()
        val r = (marginPx + rectWidth).toFloat()
        val b = (marginPx + rectHeight).toFloat()
        for ((index, c) in handleCenters(l, t, r, b).withIndex()) {
            val dx = x - c.first
            val dy = y - c.second
            if (dx * dx + dy * dy <= (handleRadius * 1.8f) * (handleRadius * 1.8f)) {
                return when (index) {
                    0 -> Mode.NW
                    1 -> Mode.N
                    2 -> Mode.NE
                    3 -> Mode.E
                    4 -> Mode.SE
                    5 -> Mode.S
                    6 -> Mode.SW
                    else -> Mode.W
                }
            }
        }
        val edgeTouch = 12f * density
        val nearLeft = abs(x - l) <= edgeTouch
        val nearRight = abs(x - r) <= edgeTouch
        val nearTop = abs(y - t) <= edgeTouch
        val nearBottom = abs(y - b) <= edgeTouch
        val inside = x >= l && x <= r && y >= t && y <= b
        return when {
            inside && nearTop && nearLeft -> Mode.NW
            inside && nearTop && nearRight -> Mode.NE
            inside && nearBottom && nearLeft -> Mode.SW
            inside && nearBottom && nearRight -> Mode.SE
            inside && nearTop -> Mode.N
            inside && nearBottom -> Mode.S
            inside && nearLeft -> Mode.W
            inside && nearRight -> Mode.E
            inside -> Mode.MOVE
            else -> Mode.NONE
        }
    }
}

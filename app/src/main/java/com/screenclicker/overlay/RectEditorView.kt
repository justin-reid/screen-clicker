package com.screenclicker.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.screenclicker.model.PxRect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Full-screen plain View (not Compose — see AGENTS.md: overlay windows are plain Views)
 * for dragging and resizing the rule's rectangles over the live app.
 *
 * One box is "active" at a time (chosen by the toolbar); only it reacts to touches.
 * Handles: corner circles resize two edges, edge strips resize one, body drag moves.
 * Rectangles never invert: dragging past the opposite edge clamps at [MIN_SIZE].
 */
class RectEditorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    enum class Role { SEARCH, TEMPLATE, CLICK }

    class Box(val role: Role, val rect: PxRect, val label: String)

    private enum class Mode { NONE, MOVE, NW, N, NE, E, SE, S, SW, W }

    var boxes: List<Box> = emptyList()
    var activeRole: Role? = null
        set(value) {
            field = value
            invalidate()
        }
    var onRectChanged: ((Role, PxRect) -> Unit)? = null

    private val screenW = resources.displayMetrics.widthPixels
    private val screenH = resources.displayMetrics.heightPixels
    private val density = resources.displayMetrics.density

    private val handleRadius = 16f * density
    private val edgeTouch = 14f * density
    private val minSize = 16

    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        color = Color.WHITE
    }
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = 0x66FFFFFF
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

    private var mode = Mode.NONE
    private var dragOffsetX = 0f
    private var dragOffsetY = 0f
    private var working: PxRect? = null

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (box in boxes) {
            val isActive = box.role == activeRole
            val paint = if (isActive) activePaint else dimPaint
            canvas.drawRect(
                box.rect.left.toFloat(),
                box.rect.top.toFloat(),
                box.rect.right.toFloat(),
                box.rect.bottom.toFloat(),
                paint,
            )
            if (isActive) {
                canvas.drawRect(
                    box.rect.left.toFloat(),
                    box.rect.top.toFloat(),
                    box.rect.right.toFloat(),
                    box.rect.bottom.toFloat(),
                    fillPaint,
                )
                for ((hx, hy) in handleCenters(box.rect)) {
                    canvas.drawCircle(hx, hy, handleRadius, handlePaint)
                }
                canvas.drawText(
                    box.label,
                    box.rect.left + 4f * density,
                    max(14f * density, box.rect.top - 6f * density),
                    labelPaint,
                )
            }
        }
    }

    private fun handleCenters(rect: PxRect): List<Pair<Float, Float>> = listOf(
        rect.left.toFloat() to rect.top.toFloat(),
        rect.centerX.toFloat() to rect.top.toFloat(),
        rect.right.toFloat() to rect.top.toFloat(),
        rect.right.toFloat() to rect.centerY.toFloat(),
        rect.right.toFloat() to rect.bottom.toFloat(),
        rect.centerX.toFloat() to rect.bottom.toFloat(),
        rect.left.toFloat() to rect.bottom.toFloat(),
        rect.left.toFloat() to rect.centerY.toFloat(),
    )

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val active = activeRole ?: return false
        val box = boxes.firstOrNull { it.role == active } ?: return false
        val x = event.x
        val y = event.y

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mode = hitMode(box.rect, x, y)
                if (mode == Mode.NONE) {
                    // Tapping empty space still selects nothing to drag, but consume the
                    // event so the app below is not accidentally operated while editing.
                    return true
                }
                dragOffsetX = x
                dragOffsetY = y
                working = box.rect
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val start = working ?: return true
                val dx = x - dragOffsetX
                val dy = y - dragOffsetY
                dragOffsetX = x
                dragOffsetY = y
                working = applyDrag(start, dx, dy)
                commit(working!!)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                working?.let { commit(it) }
                working = null
                mode = Mode.NONE
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun commit(rect: PxRect) {
        val role = activeRole ?: return
        onRectChanged?.invoke(role, rect)
        invalidate()
    }

    private fun applyDrag(start: PxRect, dx: Float, dy: Float): PxRect {
        val l = start.left
        val t = start.top
        val r = start.right
        val b = start.bottom
        val min = minSize
        return when (mode) {
            Mode.MOVE -> PxRect(
                (l + dx.toInt()).coerceIn(0, screenW - start.width),
                (t + dy.toInt()).coerceIn(0, screenH - start.height),
                (r + dx.toInt()).coerceIn(start.width, screenW),
                (b + dy.toInt()).coerceIn(start.height, screenH),
            )

            Mode.NW -> PxRect(
                (l + dx.toInt()).coerceIn(0, r - min),
                (t + dy.toInt()).coerceIn(0, b - min),
                r,
                b,
            )

            Mode.N -> PxRect(l, (t + dy.toInt()).coerceIn(0, b - min), r, b)
            Mode.S -> PxRect(l, t, r, (b + dy.toInt()).coerceIn(t + min, screenH))
            Mode.W -> PxRect((l + dx.toInt()).coerceIn(0, r - min), t, r, b)
            Mode.E -> PxRect(l, t, (r + dx.toInt()).coerceIn(l + min, screenW), b)

            Mode.NE -> PxRect(
                l,
                (t + dy.toInt()).coerceIn(0, b - min),
                (r + dx.toInt()).coerceIn(l + min, screenW),
                b,
            )

            Mode.SE -> PxRect(
                l,
                t,
                (r + dx.toInt()).coerceIn(l + min, screenW),
                (b + dy.toInt()).coerceIn(t + min, screenH),
            )

            Mode.SW -> PxRect(
                (l + dx.toInt()).coerceIn(0, r - min),
                t,
                r,
                (b + dy.toInt()).coerceIn(t + min, screenH),
            )

            Mode.NONE -> start
        }
    }

    private fun hitMode(rect: PxRect, x: Float, y: Float): Mode {
        val hx = handleCenters(rect)
        for ((index, c) in hx.withIndex()) {
            val ddx = x - c.first
            val ddy = y - c.second
            if (ddx * ddx + ddy * ddy <= (handleRadius * 1.6f) * (handleRadius * 1.6f)) {
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
        val nearLeft = abs(x - rect.left) <= edgeTouch
        val nearRight = abs(x - rect.right) <= edgeTouch
        val nearTop = abs(y - rect.top) <= edgeTouch
        val nearBottom = abs(y - rect.bottom) <= edgeTouch
        val inside = x >= rect.left && x <= rect.right && y >= rect.top && y <= rect.bottom
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

    companion object {
        /** Convenience for building boxes with sane defaults. */
        fun defaultRect(fraction: Float, screenW: Int, screenH: Int): PxRect {
            val w = (screenW * fraction).toInt().coerceAtLeast(40)
            val h = (screenH * fraction).toInt().coerceAtLeast(40)
            val left = max(0, (screenW - w) / 2)
            val top = max(0, (screenH - h) / 2)
            return PxRect(left, top, min(left + w, screenW), min(top + h, screenH))
        }
    }
}

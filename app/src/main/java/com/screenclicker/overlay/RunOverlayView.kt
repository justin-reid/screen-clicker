package com.screenclicker.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.View
import com.screenclicker.vision.MatchResult

/**
 * The run overlay: what the runner matched (a box and a score per find) and where it tapped.
 *
 * Owned by [com.screenclicker.accessibility.ClickerAccessibilityService]. The window is opened
 * for the whole run whatever the two settings say — they only drive what is drawn and the
 * layer's visibility, which is what lets either be switched mid-run instead of at the next
 * Start. Both switches are on the floating control panel and on the app's settings screen.
 *
 * The tap layer answers a question the status line cannot. The panel reports one coordinate at
 * a time, and one coordinate never shows whether the point is drawn at random inside the area
 * or is always the centre. So every injected tap leaves a marker for a couple of seconds, with
 * the area it came from outlined beneath it: a run of taps reads as a scatter across the area,
 * and a marker that never moves is the bug, visible at a glance.
 *
 * [TapTrace] holds the expiry and fade rules (pure Kotlin, unit-tested); this class only
 * paints them.
 */
class RunOverlayView(context: Context) : View(context) {

    @Volatile
    private var matches: List<MatchResult> = emptyList()

    /**
     * The user's "show taps" choice. Set from the main thread while the runner reads nothing
     * here, so the plain @Volatile the match list uses is enough.
     */
    @Volatile
    var showTaps: Boolean = false

    private val trace = TapTrace()

    private val density = resources.displayMetrics.density

    private val matchStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        color = Color.rgb(0, 230, 200)
    }
    private val matchFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x3000E6C8
    }
    private val matchText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0, 230, 200)
        textSize = 13f * density
    }

    /** The region a tap was drawn from: amber, so it cannot be confused with a match box. */
    private val areaStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = Color.rgb(255, 213, 79)
    }
    private val tapDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val tapRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
    }

    /** Thread-safe: the runner calls this from a coroutine. */
    fun update(newMatches: List<MatchResult>) {
        matches = newMatches
        postInvalidateOnAnimation()
    }

    /**
     * Thread-safe: called from the runner's dispatcher, once per injected tap. The trace is
     * locked rather than posted to the main thread because the marks carry the runner's own
     * timestamps, and re-stamping them on arrival would make the fade depend on scheduling.
     */
    fun addTap(mark: TapMark) {
        synchronized(trace) { trace.add(mark) }
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (match in matches) {
            val left = match.left.toFloat()
            val top = match.top.toFloat()
            val right = (match.left + match.width).toFloat()
            val bottom = (match.top + match.height).toFloat()
            canvas.drawRect(left, top, right, bottom, matchFill)
            canvas.drawRect(left, top, right, bottom, matchStroke)
            canvas.drawText(
                "${(match.score * 100).toInt()}%",
                left + 4f * density,
                maxOf(14f * density, top - 6f * density),
                matchText,
            )
        }
        if (!showTaps) return
        drawTaps(canvas)
    }

    /**
     * A marker per live tap, over the outline of the area it was drawn from.
     *
     * The outlines are deduplicated by rect: a rule tapping the same area twice a second would
     * otherwise stack a translucent rectangle on itself sixty deep, and stacked alpha reads as
     * a solid amber block rather than the area's edge.
     */
    private fun drawTaps(canvas: Canvas) {
        val now = SystemClock.elapsedRealtime()
        val live = synchronized(trace) { trace.live(now) }
        if (live.isEmpty()) return

        val outlines = LinkedHashMap<com.screenclicker.model.PxRect, Float>()
        for (mark in live) {
            val fade = trace.fade(mark, now)
            outlines[mark.bounds] = maxOf(outlines[mark.bounds] ?: 0f, fade)
            val color = if (mark.acted) LANDED else REFUSED
            val x = mark.x.toFloat()
            val y = mark.y.toFloat()
            val alpha = (255 * fade).toInt()
            tapRing.color = color
            tapRing.alpha = alpha
            canvas.drawCircle(x, y, 11f * density, tapRing)
            tapDot.color = color
            tapDot.alpha = alpha
            // A landed tap gets a solid centre; a refused one a pinhole, so "where it was
            // aimed" is still legible but cannot be mistaken for a tap that happened.
            canvas.drawCircle(x, y, (if (mark.acted) 4f else 2f) * density, tapDot)
        }
        for ((bounds, fade) in outlines) {
            areaStroke.alpha = (150 * fade).toInt()
            canvas.drawRect(
                bounds.left.toFloat(),
                bounds.top.toFloat(),
                bounds.right.toFloat(),
                bounds.bottom.toFloat(),
                areaStroke,
            )
        }
        // Keep animating while anything is alive: this is what makes the markers fade out,
        // with no timer that could outlive the run and keep waking the CPU.
        postInvalidateOnAnimation()
    }

    private companion object {
        /** A tap the system took: the same green the panel uses for "running". */
        const val LANDED = 0xFF69F0AE.toInt()

        /** Aimed but not taken: grey, so it cannot be read as a tap that landed. */
        const val REFUSED = 0xFFB0BEC5.toInt()
    }
}

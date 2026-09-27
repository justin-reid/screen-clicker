package com.screenclicker.overlay

import android.graphics.Canvas
import android.graphics.Paint
import com.screenclicker.model.PxRect
import kotlin.math.abs

/**
 * Alignment probe — the empirical answer to "where did the drawn rectangle really end up?"
 *
 * Overlay window coordinates and screenshot coordinates do not reliably share an origin. An
 * overlay's `y` can be measured from below the status bar, and on a foldable the window
 * manager may lay windows out against a differently sized display than the service assumes.
 * Both cases look identical from the outside: the captured crop is not the content inside the
 * drawn rectangle, by a constant offset that cannot be derived from any API we can read (and
 * guessing at it — as an earlier attempt did by re-positioning the windows — makes it worse,
 * because the window manager then applies its own offset on top of the correction).
 *
 * So it is measured instead. A small magenta/cyan checkerboard is painted inside the
 * rectangle's handle margin — deliberately *outside* the area that gets cropped — and then
 * found again in the screenshot itself, in the screenshot's own coordinate space. The
 * difference between where it was found and where the model expected it is the correction
 * applied to crops, search regions and click regions.
 */
object ProbeMarker {

    /** Footprint in pixels: [CELLS] x [CELLS] cells of [CELL] px. */
    const val SIZE = 16
    private const val CELL = 4
    private const val CELLS = SIZE / CELL

    /** Inset from the window edge. Must stay inside the service's handle margin. */
    private const val INSET = 2

    private const val MAGENTA = 0xFFFF00FF.toInt()
    private const val CYAN = 0xFF00FFFF.toInt()

    /** Screenshots can pass through a colour-space conversion; compare RGB loosely. */
    private const val TOLERANCE = 32

    /**
     * Upper bound on candidates given the full 16x16 verification. A screen with a large
     * magenta area would otherwise make this scan unbounded; bailing keeps the capture
     * responsive and simply leaves the alignment unprobed (the caller falls back).
     */
    private const val MAX_CANDIDATES = 20_000

    /**
     * Where to paint the marker inside a window that hugs `rect` plus its `marginPx` ring, in
     * window coordinates, or null when there is nowhere to put it.
     *
     * Vertically it must sit in the top or bottom margin band — that is what keeps it out of
     * the cropped area — but horizontally it may be anywhere inside the window. That is what
     * makes a rectangle spanning the full screen width probe-able at all: both of its side
     * margins are off screen, so a corner would be clipped.
     *
     * A rectangle flush against both screen edges leaves no visible margin anywhere. Painting
     * the marker inside the crop there would put the probe into the captured template, so null
     * is returned, nothing is painted, and the caller falls back to the window manager's own
     * offset (see ConfigOverlayService.measureAlignment).
     *
     * Computed once, here, and handed to the view: the painted position and the expected
     * position are then the same number rather than two derivations that can drift.
     */
    fun offsetInWindow(
        rect: PxRect,
        marginPx: Int,
        screenWidth: Int,
        screenHeight: Int,
    ): Pair<Int, Int>? {
        val windowLeft = rect.left - marginPx
        val windowTop = rect.top - marginPx
        val windowWidth = rect.width + 2 * marginPx
        val windowHeight = rect.height + 2 * marginPx

        // On screen *and* outside the cropped rows.
        fun usable(y: Int): Boolean =
            windowTop + y >= 0 && windowTop + y + SIZE <= screenHeight &&
                (y + SIZE <= marginPx || y >= marginPx + rect.height)

        val topBandY = INSET
        val bottomBandY = windowHeight - INSET - SIZE
        val y = when {
            usable(topBandY) -> topBandY
            usable(bottomBandY) -> bottomBandY
            else -> return null
        }

        val minX = (-windowLeft + INSET).coerceAtLeast(INSET)
        val maxX = (screenWidth - windowLeft - SIZE)
            .coerceAtMost(windowWidth - INSET - SIZE)
        if (maxX < minX) return null
        val preferredX = (windowWidth - SIZE) / 2
        return preferredX.coerceIn(minX, maxX) to y
    }

    /**
     * Where the marker appears on screen when the window is drawn exactly where the model
     * rect says it is. The gap against [locate]'s result is the correction.
     */
    fun expectedPosition(rect: PxRect, marginPx: Int, offset: Pair<Int, Int>): Pair<Int, Int> =
        (rect.left - marginPx + offset.first) to (rect.top - marginPx + offset.second)

    /** Paints the checkerboard. [paint] must be a non-antialiased FILL paint. */
    fun draw(canvas: Canvas, left: Int, top: Int, paint: Paint) {
        for (cellY in 0 until CELLS) {
            for (cellX in 0 until CELLS) {
                paint.color = if ((cellX + cellY) % 2 == 0) MAGENTA else CYAN
                val x = left + cellX * CELL
                val y = top + cellY * CELL
                canvas.drawRect(
                    x.toFloat(),
                    y.toFloat(),
                    (x + CELL).toFloat(),
                    (y + CELL).toFloat(),
                    paint,
                )
            }
        }
    }

    /**
     * Finds the marker in a full-screen capture and returns its top-left in image
     * coordinates, or null when it is not in the image (clipped at an edge, or covered by
     * another window). Scans the whole image: the offset being hunted can be a fifth of the
     * screen, so a bounded search around the expected spot would miss it.
     *
     * Ties — several matches — resolve to the one nearest [expectedX]/[expectedY].
     */
    fun locate(
        argb: IntArray,
        width: Int,
        height: Int,
        expectedX: Int,
        expectedY: Int,
    ): Pair<Int, Int>? {
        if (width < SIZE || height < SIZE) return null
        var best: Pair<Int, Int>? = null
        var bestDistance = Int.MAX_VALUE
        var candidates = 0
        for (y in 0..height - SIZE) {
            val row = y * width
            for (x in 0..width - SIZE) {
                // Cheap gate: the top-left cell of the marker is magenta.
                if (!closeEnough(argb[row + x], MAGENTA)) continue
                if (candidates++ >= MAX_CANDIDATES) return best
                if (!matches(argb, width, x, y)) continue
                val distance = abs(x - expectedX) + abs(y - expectedY)
                if (distance < bestDistance) {
                    bestDistance = distance
                    best = x to y
                }
            }
        }
        return best
    }

    /**
     * Verifies every pixel of all 16 cells.
     *
     * Not a sample of the cell centres: a 4px cell sampled at its centre still matches one
     * pixel off, and that one pixel would be reported as window-manager displacement — a
     * systematic error in every measurement. Checking each pixel pins the marker's top-left
     * corner exactly.
     */
    private fun matches(argb: IntArray, width: Int, x: Int, y: Int): Boolean {
        for (cellY in 0 until CELLS) {
            for (cellX in 0 until CELLS) {
                val expected = if ((cellX + cellY) % 2 == 0) MAGENTA else CYAN
                for (offsetY in 0 until CELL) {
                    val row = (y + cellY * CELL + offsetY) * width
                    for (offsetX in 0 until CELL) {
                        val color = argb[row + x + cellX * CELL + offsetX]
                        if (!closeEnough(color, expected)) return false
                    }
                }
            }
        }
        return true
    }

    private fun closeEnough(color: Int, expected: Int): Boolean =
        abs(((color shr 16) and 0xFF) - ((expected shr 16) and 0xFF)) <= TOLERANCE &&
            abs(((color shr 8) and 0xFF) - ((expected shr 8) and 0xFF)) <= TOLERANCE &&
            abs((color and 0xFF) - (expected and 0xFF)) <= TOLERANCE
}

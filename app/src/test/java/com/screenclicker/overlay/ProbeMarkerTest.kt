package com.screenclicker.overlay

import com.screenclicker.model.PxRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The alignment probe is the reason a captured template matches the rectangle the user drew
 * (see [ProbeMarker]), so its geometry is pinned down here:
 *
 *  - the marker always sits fully outside the cropped rows, and fully on screen — including
 *    for a rectangle spanning the whole width, whose side margins are both off screen;
 *  - [ProbeMarker.locate] finds it exactly where it was painted, with a colour-space shift;
 *  - when the window manager draws the rectangle somewhere else, the difference between the
 *    found and the expected position *is* that displacement.
 *
 * The last one is the regression test for the reported bug: the crop landed about a fifth of
 * the screen away from the drawn box.
 */
class ProbeMarkerTest {

    private val margin = 20
    private val imageWidth = 420
    private val imageHeight = 840

    /** Middle of the screen, plus one hugging each edge and one spanning the full width. */
    private val rects = listOf(
        PxRect(100, 300, 320, 520),
        PxRect(0, 0, 200, 200),
        PxRect(imageWidth - 200, 0, imageWidth, 200),
        PxRect(0, imageHeight - 200, 200, imageHeight),
        PxRect(imageWidth - 200, imageHeight - 200, imageWidth, imageHeight),
        PxRect(10, 400, imageWidth - 10, 700),
        PxRect(0, 0, imageWidth, imageHeight),
    )

    /**
     * The documented placement rule, restated here with literal numbers so this test pins the
     * behaviour rather than calling the code it is checking: marker 16px with a 2px inset
     * (18px total) in the top margin band if that band is on screen, else the bottom band, and
     * horizontally centred but clamped so the marker stays on screen.
     */
    private fun expectedOffset(rect: PxRect): Pair<Int, Int>? {
        val windowLeft = rect.left - margin
        val windowTop = rect.top - margin
        val windowWidth = rect.width + 2 * margin
        val windowHeight = rect.height + 2 * margin
        fun usable(y: Int) = windowTop + y >= 0 && windowTop + y + 16 <= imageHeight &&
            (y + 16 <= margin || y >= margin + rect.height)
        val y = when {
            usable(2) -> 2
            usable(windowHeight - 18) -> windowHeight - 18
            else -> return null
        }
        val minX = maxOf(-windowLeft + 2, 2)
        val maxX = minOf(imageWidth - windowLeft - 16, windowWidth - 18)
        if (maxX < minX) return null
        return ((windowWidth - 16) / 2).coerceIn(minX, maxX) to y
    }

    /**
     * Paints the checkerboard the way [RectBubbleView] does, in a window that the window
     * manager may have moved by [dx]/[dy] from where the model said it was.
     */
    private fun paint(
        rect: PxRect,
        dx: Int = 0,
        dy: Int = 0,
        channelShift: Int = 0,
    ): IntArray {
        val image = IntArray(imageWidth * imageHeight) { BACKGROUND }
        val (offsetX, offsetY) = expectedOffset(rect) ?: return image
        val originX = rect.left - margin + offsetX + dx
        val originY = rect.top - margin + offsetY + dy
        for (cellY in 0 until 4) {
            for (cellX in 0 until 4) {
                val base = if ((cellX + cellY) % 2 == 0) MAGENTA else CYAN
                for (y in 0 until 4) {
                    for (x in 0 until 4) {
                        val px = originX + cellX * 4 + x
                        val py = originY + cellY * 4 + y
                        if (px !in 0 until imageWidth || py !in 0 until imageHeight) continue
                        image[py * imageWidth + px] = shift(base, channelShift)
                    }
                }
            }
        }
        return image
    }

    private fun shift(color: Int, amount: Int): Int {
        if (amount == 0) return color
        val r = (((color shr 16) and 0xFF) + amount).coerceIn(0, 255)
        val g = (((color shr 8) and 0xFF) + amount).coerceIn(0, 255)
        val b = ((color and 0xFF) + amount).coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    @Test
    fun `offset matches the documented placement rule`() {
        for (rect in rects) {
            assertEquals(
                "rect $rect",
                expectedOffset(rect),
                ProbeMarker.offsetInWindow(rect, margin, imageWidth, imageHeight),
            )
        }
    }

    @Test
    fun `marker is fully on screen and never inside the cropped rows`() {
        for (rect in rects) {
            val offset = ProbeMarker.offsetInWindow(rect, margin, imageWidth, imageHeight)
            if (offset == null) continue
            val (offsetX, offsetY) = offset
            val markerLeft = rect.left - margin + offsetX
            val markerTop = rect.top - margin + offsetY
            val markerRight = markerLeft + ProbeMarker.SIZE
            val markerBottom = markerTop + ProbeMarker.SIZE

            assertTrue(
                "marker $markerLeft..$markerRight x $markerTop..$markerBottom off screen for $rect",
                markerLeft >= 0 && markerTop >= 0 &&
                    markerRight <= imageWidth && markerBottom <= imageHeight,
            )
            // The cropped rows are the rect itself; the marker must miss them entirely,
            // otherwise the template would contain the probe.
            val overlapsCropRows = markerBottom > rect.top && markerTop < rect.bottom
            assertFalse("marker $markerTop..$markerBottom overlaps $rect", overlapsCropRows)
        }
    }

    @Test
    fun `a rect with no visible margin gets no marker instead of one inside the crop`() {
        // Flush against both screen edges: the window is larger than the screen in both axes,
        // so there is nowhere to hide a marker. Drawing one inside the rect would bake the
        // probe into the captured template.
        val flush = PxRect(0, 0, imageWidth, imageHeight)
        assertNull(ProbeMarker.offsetInWindow(flush, margin, imageWidth, imageHeight))
        assertNull(ProbeMarker.locate(paint(flush), imageWidth, imageHeight, 0, 0))
    }

    @Test
    fun `locate finds the marker where it was painted`() {
        for (rect in rects) {
            val offset = ProbeMarker.offsetInWindow(rect, margin, imageWidth, imageHeight)
            if (offset == null) continue
            val image = paint(rect)
            val expected = ProbeMarker.expectedPosition(rect, margin, offset)
            assertEquals(
                "rect $rect",
                expected,
                ProbeMarker.locate(image, imageWidth, imageHeight, expected.first, expected.second),
            )
        }
    }

    @Test
    fun `locate reports how far the window was actually drawn from the model`() {
        val rect = PxRect(100, 300, 320, 520)
        val offset = ProbeMarker.offsetInWindow(rect, margin, imageWidth, imageHeight)!!
        // Displacements of the size that was reported, bounded by the marker staying in frame.
        for ((dx, dy) in listOf(0 to -200, 0 to 140, -60 to 0, 33 to -77)) {
            val image = paint(rect, dx = dx, dy = dy)
            val (expectedX, expectedY) = ProbeMarker.expectedPosition(rect, margin, offset)
            val found = ProbeMarker.locate(image, imageWidth, imageHeight, expectedX, expectedY)
            assertEquals("offset ($dx,$dy)", expectedX + dx to expectedY + dy, found)
            assertEquals(dx, found!!.first - expectedX)
            assertEquals(dy, found.second - expectedY)
        }
    }

    @Test
    fun `a displacement that pushes the marker off screen yields no probe`() {
        // The displacement the user saw was this large, but a window drawn that far off screen
        // cannot be probed at all: measureAlignment() then falls back to the window manager's
        // own offset, and the capture still reports that it did.
        val rect = PxRect(100, 300, 320, 520)
        val offset = ProbeMarker.offsetInWindow(rect, margin, imageWidth, imageHeight)!!
        val (expectedX, expectedY) = ProbeMarker.expectedPosition(rect, margin, offset)
        val image = paint(rect, dy = -528)
        assertNull(ProbeMarker.locate(image, imageWidth, imageHeight, expectedX, expectedY))
    }

    @Test
    fun `locate survives a colour-space shift and reports nothing when the marker is absent`() {
        val rect = PxRect(100, 300, 320, 520)
        val offset = ProbeMarker.offsetInWindow(rect, margin, imageWidth, imageHeight)!!
        val (expectedX, expectedY) = ProbeMarker.expectedPosition(rect, margin, offset)
        for (shiftAmount in listOf(0, 12, -12)) {
            val image = paint(rect, channelShift = shiftAmount)
            assertEquals(
                "channel shift $shiftAmount",
                expectedX to expectedY,
                ProbeMarker.locate(image, imageWidth, imageHeight, expectedX, expectedY),
            )
        }
        val empty = IntArray(imageWidth * imageHeight) { BACKGROUND }
        assertNull(ProbeMarker.locate(empty, imageWidth, imageHeight, expectedX, expectedY))
    }

    @Test
    fun `an image smaller than the marker is handled`() {
        assertNull(ProbeMarker.locate(IntArray(0), 0, 0, 0, 0))
        assertNull(ProbeMarker.locate(IntArray(4), 2, 2, 0, 0))
    }

    private companion object {
        const val BACKGROUND = 0xFF1A1A1A.toInt()
        const val MAGENTA = 0xFFFF00FF.toInt()
        const val CYAN = 0xFF00FFFF.toInt()
    }
}

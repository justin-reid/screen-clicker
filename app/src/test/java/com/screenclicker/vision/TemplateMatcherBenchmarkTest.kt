package com.screenclicker.vision

import com.screenclicker.model.PxRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Wall-clock cost of one search, on the geometry Justin's phone reports:
 * a 1080x2520 frame with a 1080x533 search region (the "scan 3057ms — … match 2906" case).
 *
 * Not a correctness test — the point is to keep matching inside a scan budget that can
 * sample a ~1s on-screen event several times, so the assertions are deliberately loose
 * (CI machines vary) and the real numbers are printed.
 */
class TemplateMatcherBenchmarkTest {

    private fun noisyScreen(width: Int, height: Int, seed: Long): GrayImage {
        val rng = Random(seed)
        // Structure, not white noise: flat regions plus gradient blocks, like a real UI.
        return GrayImage(width, height, IntArray(width * height) { i ->
            val x = i % width
            val y = i / width
            if ((x / 90 + y / 60) % 3 == 0) 40 else ((x % 256) + (y % 97)) / 2
        })
    }

    private fun patch(from: GrayImage, left: Int, top: Int, w: Int, h: Int): GrayImage {
        val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) px[y * w + x] = from[x + left, y + top]
        return GrayImage(w, h, px)
    }

    @Test
    fun `region from the device report is searched fast enough to sample a one second event`() {
        val width = 1080
        val height = 2520
        val region = PxRect(0, 1000, 1080, 1533)
        val screen = noisyScreen(width, height, seed = 42)

        for (templateSize in intArrayOf(120, 200, 400)) {
            val template = patch(screen, 300, 1200, templateSize, templateSize)
            // Warm once so JIT has seen the loops before the measured run.
            TemplateMatcher.search(screen, template, region)
            val start = System.nanoTime()
            // search() exposes the best-effort candidate even when this synthetic UI has
            // repeated equal-looking blocks and therefore is not safe to click.
            val result = TemplateMatcher.search(screen, template, region).best
            val ms = (System.nanoTime() - start) / 1_000_000
            println("template=${templateSize}x$templateSize match=${ms}ms score=${result?.score}")
            assertNotNull(result)
            // A scan has to fit between the frames it is trying to see: the target appears for
            // about a second and a capture costs ~150ms on the phone, so several hundred ms of
            // matching would still sample it only once. This bound is loose (CI machines
            // vary) but far below the 2.9s this used to take on the device.
            assertTrue("match took ${ms}ms", ms < 400)
        }
    }

    /**
     * A button-shaped patch: flat face, bright border, darker glyph. Distinct enough that
     * the matcher has exactly one honest answer per stamped copy.
     */
    private fun buttonPatch(w: Int, h: Int, seed: Long): GrayImage {
        val rng = Random(seed)
        return GrayImage(w, h, IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val border = x < 3 || y < 3 || x >= w - 3 || y >= h - 3
            val glyph = x in (w / 3) until (2 * w / 3) && y in (h / 3) until (2 * h / 3)
            when {
                border -> 230
                glyph -> 60 + rng.nextInt(0, 40)
                else -> 150 + (x % 7)
            }
        })
    }

    @Test
    fun `a field of repeated copies in a screen-sized region stays inside the scan budget`() {
        // The geometry Justin's phone reports for the StarFarm field: a 1080x2520 frame, a
        // search region covering most of it (1075x1717) and ~30 copies of the same button on
        // screen at once. Making every occurrence a real hit (round 13) also made the
        // per-seed full-resolution refine the dominant cost of a scan — the device report was
        // "match 5878ms", six times the whole scan interval.
        val width = 1080
        val height = 2520
        val region = PxRect(0, 400, 1075, 2117)
        val background = noisyScreen(width, height, seed = 45)
        val template = buttonPatch(200, 180, seed = 11)
        val px = background.pixels.copyOf()
        var copies = 0
        var top = 440
        while (top + template.height < 2080) {
            for (col in 0 until 4) {
                val left = 30 + col * 255
                for (y in 0 until template.height) for (x in 0 until template.width) {
                    px[(top + y) * width + left + x] = template[x, y]
                }
                copies++
            }
            top += 215
        }
        val grid = GrayImage(width, height, px)

        TemplateMatcher.search(grid, template, region, 0.9f) // JIT warm-up
        val start = System.nanoTime()
        val outcome = TemplateMatcher.search(grid, template, region, 0.9f)
        val ms = (System.nanoTime() - start) / 1_000_000
        println("grid copies=$copies match=${ms}ms hits=${outcome.matches.size}")
        assertTrue(
            "found ${outcome.matches.size} of $copies copies",
            outcome.matches.size >= copies - 1,
        )
        assertTrue("grid match took ${ms}ms", ms < 1_500)
    }

    @Test
    fun `a faded target over the same region costs the second evidence pass and stays in budget`() {
        // Mid-fade the plain pass is not confident, so the high-pass pass runs at both the
        // coarse and the refine stage; this is the worst case per scan and the one that has
        // to fit the cadence. The template is cut from one screen and faded over a *different*
        // one: blending a template with the very pixels it was cropped from is no fade at all.
        val width = 1080
        val height = 2520
        val region = PxRect(0, 1000, 1080, 1533)
        val source = noisyScreen(width, height, seed = 43)
        val background = noisyScreen(width, height, seed = 44)
        val template = patch(source, 300, 1200, 120, 120)

        val fadedPx = background.pixels.copyOf()
        val alpha = 0.3f
        for (y in 0 until 120) {
            for (x in 0 until 120) {
                val idx = (1200 + y) * width + (300 + x)
                fadedPx[idx] = (alpha * template[x, y] + (1f - alpha) * background[x + 300, y + 1200])
                    .toInt().coerceIn(0, 255)
            }
        }
        val faded = GrayImage(width, height, fadedPx)

        TemplateMatcher.search(faded, template, region) // JIT warm-up
        val start = System.nanoTime()
        val outcome = TemplateMatcher.search(faded, template, region)
        val result = outcome.best
        val ms = (System.nanoTime() - start) / 1_000_000
        println("faded template=120x120 match=${ms}ms score=${result?.score} edge=${result?.viaHighPass}")
        assertNotNull(result)
        assertEquals(300, result!!.left)
        assertEquals(1200, result.top)
        assertTrue("match took ${ms}ms", ms < 400)
    }
}

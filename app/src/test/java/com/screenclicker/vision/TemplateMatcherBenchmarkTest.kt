package com.screenclicker.vision

import com.screenclicker.model.PxRect
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
            TemplateMatcher.findBest(screen, template, region, 0f)
            val start = System.nanoTime()
            val result = TemplateMatcher.findBest(screen, template, region, 0f)
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
}

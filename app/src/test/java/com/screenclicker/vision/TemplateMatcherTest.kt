package com.screenclicker.vision

import com.screenclicker.model.PxRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class TemplateMatcherTest {

    // ---------------------------------------------------------------- helpers

    private fun noiseImage(width: Int, height: Int, seed: Long): GrayImage {
        val rng = Random(seed)
        val px = IntArray(width * height) { rng.nextInt(0, 256) }
        return GrayImage(width, height, px)
    }

    private fun patch(from: GrayImage, left: Int, top: Int, w: Int, h: Int): GrayImage {
        val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) px[y * w + x] = from[x + left, y + top]
        return GrayImage(w, h, px)
    }

    /** Pastes [template] into a copy of [screen] at (left, top), clamping values. */
    private fun paste(screen: GrayImage, template: GrayImage, left: Int, top: Int, remap: (Int) -> Int = { it }): GrayImage {
        val out = screen.pixels.copyOf()
        for (y in 0 until template.height) {
            for (x in 0 until template.width) {
                val sx = left + x
                val sy = top + y
                if (sx in 0 until screen.width && sy in 0 until screen.height) {
                    out[sy * screen.width + sx] = remap(template[x, y])
                }
            }
        }
        return GrayImage(screen.width, screen.height, out)
    }

    private fun gradientScreen(width: Int, height: Int): GrayImage {
        val px = IntArray(width * height) { i ->
            val x = i % width
            val y = i / width
            ((x * 200) / width + (y * 55) / height).coerceIn(0, 255)
        }
        return GrayImage(width, height, px)
    }

    // ---------------------------------------------------------------- tests

    @Test
    fun `exact template scores one at the right position`() {
        val screen = noiseImage(200, 150, seed = 1)
        val template = patch(screen, 37, 53, 24, 18)
        val result = TemplateMatcher.findBest(screen, template, null, 0.9f)
        assertNotNull(result)
        assertEquals(37, result!!.left)
        assertEquals(53, result.top)
        assertTrue("score ${result.score}", result.score > 0.99f)
    }

    @Test
    fun `template is found inside a restricted region`() {
        val screen = noiseImage(300, 200, seed = 2)
        val template = patch(screen, 220, 150, 20, 20)
        val region = PxRect(200, 120, 280, 190)
        val result = TemplateMatcher.findBest(screen, template, region, 0.9f)
        assertNotNull(result)
        assertEquals(220, result!!.left)
        assertEquals(150, result.top)
        assertTrue(result.score > 0.99f)
    }

    @Test
    fun `match outside the region is not reported`() {
        val screen = noiseImage(300, 200, seed = 3)
        val template = patch(screen, 220, 150, 20, 20)
        val region = PxRect(0, 0, 100, 100) // does not contain the match
        val result = TemplateMatcher.findBest(screen, template, region, 0.9f)
        assertNull(result)
    }

    @Test
    fun `brightness shift still matches high`() {
        val screen = noiseImage(200, 150, seed = 4)
        val template = patch(screen, 37, 53, 24, 18)
        val brightened = paste(screen, template, 37, 53) { (it + 25).coerceAtMost(255) }
        val result = TemplateMatcher.findBest(brightened, template, null, 0.9f)
        assertNotNull(result)
        assertEquals(37, result!!.left)
        assertEquals(53, result.top)
        assertTrue("score ${result.score}", result.score > 0.95f)
    }

    @Test
    fun `contrast scaling still matches high`() {
        val screen = noiseImage(200, 150, seed = 5)
        val template = patch(screen, 37, 53, 24, 18)
        val scaled = paste(screen, template, 37, 53) { (it * 1.3f).toInt().coerceAtMost(255) }
        val result = TemplateMatcher.findBest(scaled, template, null, 0.9f)
        assertNotNull(result)
        assertEquals(37, result!!.left)
        assertTrue("score ${result.score}", result.score > 0.95f)
    }

    @Test
    fun `unrelated noise screen does not match`() {
        val template = patch(noiseImage(60, 60, seed = 10), 5, 5, 40, 40)
        val screen = noiseImage(300, 200, seed = 11) // different noise entirely
        val result = TemplateMatcher.findBest(screen, template, null, 0.9f)
        assertNull(result)
    }

    @Test
    fun `template larger than region returns null`() {
        val screen = noiseImage(100, 100, seed = 6)
        // A template cut from inside the region must be found there...
        val fitting = patch(screen, 10, 10, 50, 50)
        val region = PxRect(10, 10, 60, 60)
        val found = TemplateMatcher.findBest(screen, fitting, region, 0.9f)
        assertNotNull(found)
        assertEquals(10, found!!.left)
        // ...while one larger than the region can never fit.
        val oversized = patch(screen, 0, 0, 60, 60)
        assertNull(TemplateMatcher.findBest(screen, oversized, region, 0.9f))
    }

    @Test
    fun `a featureless template is reported as having no contrast`() {
        // A crop of nothing but background cannot say where anything is, however well some
        // patch of screen happens to correlate with it. This is the shape of a template
        // captured while the image was invisible.
        val gradient = gradientScreen(200, 150)
        val flat = GrayImage(16, 16, IntArray(16 * 16) { 100 })
        val screen = paste(gradient, flat, 90, 60)
        val outcome = TemplateMatcher.search(screen, flat, null)
        assertEquals(Confidence.NO_CONTRAST, outcome.confidence)
        assertNull(outcome.best)
        assertNull(TemplateMatcher.findBest(screen, flat, null, 0f))
    }

    @Test
    fun `a fade is matched at its true position, not the brightest patch`() {
        // The image fades in over a flat background: window = alpha * template + (1-alpha) * bg.
        // That is an affine change of the template, so ZNCC is invariant to it and the peak
        // stays on the image — which is the whole reason an image that fades can be clicked.
        val background = 90
        val pattern = GrayImage(40, 30, IntArray(40 * 30) { i ->
            val x = i % 40
            val y = i / 40
            (30 + (x * 5) % 140 + ((y / 3) * 9) % 60).coerceIn(0, 255)
        })
        val screen = GrayImage(200, 150, IntArray(200 * 150) { background })
        for (alpha in listOf(1.0f, 0.6f, 0.3f)) {
            val faded = paste(screen, pattern, 60, 40) { v ->
                (v * alpha + background * (1f - alpha)).toInt().coerceIn(0, 255)
            }
            val result = TemplateMatcher.findBest(faded, pattern, null, 0.9f)
            assertNotNull("fade alpha=$alpha", result)
            assertEquals("fade alpha=$alpha", 60, result!!.left)
            assertEquals("fade alpha=$alpha", 40, result.top)
            assertTrue("fade alpha=$alpha score ${result.score}", result.score > 0.9f)
        }
    }

    @Test
    fun `a tie between mediocre candidates is not reported as a location`() {
        // Stripes repeat every 20px, so every stripe window fits the (unrelated, low-contrast)
        // template equally well. Nothing here is the image, and the search must say so rather
        // than hand back whichever candidate happened to be first: that is a highlight drawn
        // where the image is not, which is worse than not finding it.
        val width = 200
        val height = 150
        val stripes = GrayImage(width, height, IntArray(width * height) { i ->
            if ((i % width / 10) % 2 == 0) 0 else 20
        })
        val texture = GrayImage(20, 20, IntArray(20 * 20) { i -> 100 + (i % 7) * 2 })
        val outcome = TemplateMatcher.search(stripes, texture, null)
        assertNotNull("expected a best-effort candidate", outcome.best)
        assertTrue("score ${outcome.best!!.score}", outcome.best!!.score < 0.95f)
        assertEquals(outcome.best!!.score, outcome.runnerUp, 0.001f)
        assertEquals(Confidence.AMBIGUOUS, outcome.confidence)
        assertNull(TemplateMatcher.findBest(stripes, texture, null, 0f))
    }

    @Test
    fun `tiny template is found through the small-template path`() {
        val screen = noiseImage(120, 90, seed = 7)
        val template = patch(screen, 61, 41, 5, 5)
        val result = TemplateMatcher.findBest(screen, template, null, 0.9f)
        assertNotNull(result)
        assertEquals(61, result!!.left)
        assertEquals(41, result.top)
    }

    @Test
    fun `full screen scan of a large region finishes fast`() {
        val screen = noiseImage(720, 1280, seed = 8)
        val template = patch(screen, 500, 900, 48, 48)
        val start = System.nanoTime()
        val result = TemplateMatcher.findBest(screen, template, null, 0.9f)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertNotNull(result)
        assertEquals(500, result!!.left)
        assertEquals(900, result.top)
        assertTrue("scan took ${elapsedMs}ms", elapsedMs < 5_000)
    }

    @Test
    fun `odd-sized images and off-grid matches still refine to the right pixel`() {
        // 331x277: odd dimensions force downsample edge cases.
        val screen = noiseImage(331, 277, seed = 9)
        val left = 173
        val top = 121
        val template = patch(screen, left, top, 33, 29)
        val result = TemplateMatcher.findBest(screen, template, null, 0.9f)
        assertNotNull(result)
        assertEquals(left, result!!.left)
        assertEquals(top, result.top)
    }

    @Test
    fun `a large template on an off-grid position still refines to the exact pixel`() {
        // 80px is over the quarter-resolution threshold, so the coarse pass can only land on a
        // multiple of 4: the refine window has to cover the bias, or an image would be reported
        // up to three pixels away from where it is.
        val screen = noiseImage(400, 300, seed = 21)
        val left = 173
        val top = 121
        val template = patch(screen, left, top, 80, 80)
        val result = TemplateMatcher.findBest(screen, template, null, 0.9f)
        assertNotNull(result)
        assertEquals(left, result!!.left)
        assertEquals(top, result.top)
        assertTrue("score ${result.score}", result.score > 0.99f)
    }

    @Test
    fun `threshold filters weak matches`() {
        val screen = noiseImage(200, 150, seed = 12)
        val template = patch(screen, 37, 53, 24, 18)
        // Same screen, so best possible score is 1.0; threshold 1.01 can never pass.
        assertNull(TemplateMatcher.findBest(screen, template, null, 1.01f))
        // minScore of exactly 0 still yields the best location.
        val lenient = TemplateMatcher.findBest(screen, template, null, 0f)
        assertNotNull(lenient)
        assertEquals(37, lenient!!.left)
    }
}

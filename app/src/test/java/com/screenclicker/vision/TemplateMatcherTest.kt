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
    fun `uniform template matches via MAD fallback`() {
        val gradient = gradientScreen(200, 150)
        val flat = GrayImage(16, 16, IntArray(16 * 16) { 100 })
        val screen = paste(gradient, flat, 90, 60)
        val result = TemplateMatcher.findBest(screen, flat, null, 0.95f)
        assertNotNull(result)
        assertEquals(90, result!!.left)
        assertEquals(60, result.top)
        assertTrue("score ${result.score}", result.score > 0.99f)
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

package com.screenclicker.vision

import com.screenclicker.model.PxRect
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the cost of one search actually goes, measured at the geometry the phone reported for
 * the StarFarm field (1080x2520 frame, a 1075x1717 search region, a 200x180 template, ~32
 * buttons on screen at once).
 *
 * This exists because the device reports `match 5161ms` and the answer decides which fix is
 * the right one. Three candidate causes with three different fixes: the *area* of the search
 * region (shrink the work per pixel), the *number of near-threshold peaks* that get refined at
 * full resolution (stop paying for peaks that are not the target), and the *implementation*
 * (~30x slower per operation on the device than the same arithmetic is here — see AGENTS.md).
 *
 * The numbers are printed on every run; the assertions are deliberately loose (CI machines
 * vary) and exist only to catch an order-of-magnitude regression, as in
 * [TemplateMatcherBenchmarkTest].
 */
class SearchCostBreakdownTest {

    private val screenWidth = 1080
    private val screenHeight = 2520

    /** The region the phone reported for the StarFarm field: most of the screen. */
    private val fullRegion = PxRect(0, 400, 1075, 2117)

    private fun background(): GrayImage =
        GrayImage(screenWidth, screenHeight, IntArray(screenWidth * screenHeight) { i ->
            val x = i % screenWidth
            val y = i / screenWidth
            if ((x / 90 + y / 60) % 3 == 0) 40 else ((x % 256) + (y % 97)) / 2
        })

    /**
     * A button: bright frame, textured face, one dark glyph block. The true template and the
     * decoys differ *only* in the glyph, which is how a real game screen behaves — every
     * button on it shares the frame and the face, so ZNCC scores them alike. That is the
     * situation the round-13 note describes ("the Plant template scores ~0.84 on other
     * buttons"), and the whole reason a scan can end up refining far more peaks than it has
     * real matches.
     */
    private fun button(w: Int, h: Int, gx: Int, gy: Int, gw: Int, gh: Int): GrayImage =
        GrayImage(w, h, IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            when {
                x < 3 || y < 3 || x >= w - 3 || y >= h - 3 -> 230
                x in gx until (gx + gw) && y in gy until (gy + gh) -> 70
                else -> 150 + (x % 7)
            }
        })

    private fun stamped(vararg patches: Pair<GrayImage, Pair<Int, Int>>): GrayImage {
        val px = background().pixels.copyOf()
        for ((t, at) in patches) {
            val (left, top) = at
            for (y in 0 until t.height) for (x in 0 until t.width) {
                px[(top + y) * screenWidth + left + x] = t[x, y]
            }
        }
        return GrayImage(screenWidth, screenHeight, px)
    }

    /** The button slots the StarFarm field has on screen at once. */
    private fun slots(): List<Pair<Int, Int>> {
        val out = mutableListOf<Pair<Int, Int>>()
        var top = 440
        while (top + 180 < 2080) {
            for (col in 0 until 4) out += (30 + col * 255) to top
            top += 215
        }
        return out
    }

    private fun measure(
        label: String,
        screen: GrayImage,
        template: GrayImage,
        region: PxRect,
    ): Long {
        TemplateMatcher.search(screen, template, region, 0.9f) // JIT warm-up
        val start = System.nanoTime()
        val out = TemplateMatcher.search(screen, template, region, 0.9f)
        val ms = (System.nanoTime() - start) / 1_000_000
        println(
            "cost $label match=${ms}ms hits=${out.matches.size} " +
                "best=${out.best?.score} conf=${out.confidence}",
        )
        return ms
    }

    @Test
    fun `a search costs search-region area plus one full-resolution refine per peak near the threshold`() {
        val button = button(200, 180, 66, 60, 66, 60)
        val decoy = button(200, 180, 84, 60, 44, 60)
        val all = slots()
        val oneCopy = stamped(button to all[0])
        val field = stamped(
            *all.mapIndexed { i, at -> (if (i == 0) button else decoy) to at }.toTypedArray(),
        )
        val decoysOnly = stamped(*all.drop(1).map { decoy to it }.toTypedArray())
        val aroundOne = PxRect(
            all[0].first - 60, all[0].second - 60,
            all[0].first + 260, all[0].second + 240,
        )

        println("--- 200x180 template, 1075x1717 region (the device's StarFarm geometry) ---")
        // Empty screen: the coarse pass over the region and nothing else. This is the floor
        // cost of *looking*, independent of the template, and it scales with region area.
        val empty = measure("empty screen, full region          ", background(), button, fullRegion)
        val oneFull = measure("1 true copy, full region           ", oneCopy, button, fullRegion)
        val fieldFull = measure("1 true copy + 31 decoys, full region", field, button, fullRegion)
        // Nothing matches confidently here, so the high-pass rescue re-refines the whole peak
        // set: the *worst* case is the frame where the target is absent, not the one where it
        // is present.
        val decoysFull = measure("31 decoys only, full region        ", decoysOnly, button, fullRegion)
        val oneSmall = measure("1 true copy, 320x300 region        ", oneCopy, button, aroundOne)
        val fieldSmall = measure("1 true + 31 decoys, 320x300 region ", field, button, aroundOne)

        // Region area is the term a user can change without changing what the app can find:
        // the same screen and template, searched over 19x less area, is an order of magnitude
        // cheaper. If it were not, "draw a smaller region" would be advice rather than an
        // admission.
        assertTrue(
            "full region ($oneFull ms) should dwarf the 320x300 region ($oneSmall ms)",
            oneFull > oneSmall * 3,
        )
        // Decoys that are not matches still cost real time: every one of them is refined at
        // full resolution before it is rejected. Order-of-magnitude guard only.
        assertTrue("field of near-copies took ${fieldFull}ms", fieldFull < 2_000)
        assertTrue("decoys-only frame took ${decoysFull}ms", decoysFull < 3_000)
        assertTrue("empty region took ${empty}ms", empty < 1_500)
        assertTrue("small region took ${fieldSmall}ms", fieldSmall < 500)
    }

    @Test
    fun `a small template over the same region is cheaper than a large one`() {
        // The per-position cost is the template's area, so the two ends of the size range are
        // worth printing together: they are what "use a smaller crop" trades against.
        val big = button(200, 180, 66, 60, 66, 60)
        val small = button(80, 80, 26, 24, 26, 24)
        val all = slots()

        val bigField = stamped(
            *all.mapIndexed { i, at -> (if (i == 0) big else button(200, 180, 84, 60, 44, 60)) to at }.toTypedArray(),
        )
        val smallField = stamped(
            *all.mapIndexed { i, at -> (if (i == 0) small else button(80, 80, 34, 24, 16, 24)) to at }.toTypedArray(),
        )

        println("--- same region, template size varied ---")
        val bigMs = measure("200x180 template, 32 buttons       ", bigField, big, fullRegion)
        val smallMs = measure("80x80 template, 32 buttons         ", smallField, small, fullRegion)

        assertTrue("200x180 took ${bigMs}ms", bigMs < 2_000)
        assertTrue("80x80 took ${smallMs}ms", smallMs < 2_000)
    }
}

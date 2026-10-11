package com.screenclicker.overlay

import com.screenclicker.model.PxRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "show taps" markers are how the user checks that a click is placed at random inside the
 * click area rather than always at its centre, so the record behind them is pinned here:
 *
 *  - several taps are visible together (the only way a spread can be read);
 *  - they clear themselves at the end of their lifetime, in order, and stay gone;
 *  - the list cannot grow without bound, however long a run goes on;
 *  - a mark fades from fully opaque to fully transparent across that lifetime.
 */
class TapTraceTest {

    private val area = PxRect(150, 120, 220, 175)

    private fun mark(x: Int, y: Int, at: Long, acted: Boolean = true) =
        TapMark(x = x, y = y, bounds = area, acted = acted, at = at)

    @Test
    fun `live returns the taps in the order they landed`() {
        val trace = TapTrace()
        trace.add(mark(160, 130, at = 1_000))
        trace.add(mark(170, 140, at = 1_100))
        trace.add(mark(180, 150, at = 1_200))

        assertEquals(listOf(160 to 130, 170 to 140, 180 to 150), trace.live(1_200).map { it.x to it.y })
    }

    @Test
    fun `a tap clears itself at the end of its lifetime and does not come back`() {
        val trace = TapTrace(limit = 10, ttlMs = 1_000)
        trace.add(mark(160, 130, at = 5_000))

        assertEquals(1, trace.live(5_999).size)
        // Exactly at the lifetime it is done — a marker lingering one frame longer than its
        // own fade would read as a fresher tap than it is.
        assertEquals(0, trace.live(6_000).size)
        // Dropped, not merely filtered: it must not reappear when the clock does not advance.
        assertEquals(0, trace.live(6_000).size)
        assertEquals(0, trace.live(9_000).size)
    }

    @Test
    fun `only the fresh taps outlive an older one`() {
        val trace = TapTrace(ttlMs = 1_000)
        trace.add(mark(160, 130, at = 0))
        trace.add(mark(200, 160, at = 900))

        // Oldest first, so the expiring prefix drops and the newcomer stays.
        assertEquals(listOf(200 to 160), trace.live(1_000).map { it.x to it.y })
    }

    @Test
    fun `the record is bounded however many taps a long run fires`() {
        val trace = TapTrace(limit = 5, ttlMs = 100_000)
        for (i in 1..50) trace.add(mark(i, i, at = i.toLong()))

        val live = trace.live(50)
        assertEquals(5, live.size)
        // The newest are the ones kept: they are what the user is looking at.
        assertEquals(listOf(46, 47, 48, 49, 50), live.map { it.x })
    }

    @Test
    fun `a mark fades from opaque to transparent across its lifetime`() {
        val trace = TapTrace(ttlMs = 1_000)
        val mark = mark(160, 130, at = 10_000)

        assertEquals(1f, trace.fade(mark, 10_000), 0.0001f)
        assertEquals(0.5f, trace.fade(mark, 10_500), 0.0001f)
        assertEquals(0f, trace.fade(mark, 11_000), 0.0001f)
        // Clamped at both ends: a marker cannot be brighter than new, or negative alpha.
        assertEquals(0f, trace.fade(mark, 12_000), 0.0001f)
        val early = mark(160, 130, at = 10_000)
        assertTrue(trace.fade(early, 9_000) <= 1f)
    }

    @Test
    fun `clear drops everything, as a new run must not inherit the last one's taps`() {
        val trace = TapTrace(ttlMs = 100_000)
        trace.add(mark(160, 130, at = 0))
        trace.add(mark(170, 140, at = 1))
        trace.clear()

        assertEquals(0, trace.live(2).size)
    }
}

package com.screenclicker.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every way a tap can fail has to be nameable on its own. Collapsing them into a boolean is
 * what let a service that was never permitted to inject gestures look identical to one whose
 * gesture was merely interrupted — for several rounds.
 */
class TapOutcomeTest {

    @Test
    fun `only a delivered gesture counts as having acted`() {
        assertTrue(TapOutcome.DELIVERED.acted)
        TapOutcome.values()
            .filter { it != TapOutcome.DELIVERED }
            .forEach { assertFalse(it.name, it.acted) }
    }

    @Test
    fun `each outcome reports a distinct reason`() {
        val reports = TapOutcome.values().map { it.report }
        assertEquals(TapOutcome.values().size, reports.toSet().size)
        assertTrue(reports.none { it.isBlank() })
        assertEquals("gesture delivered", TapOutcome.DELIVERED.report)
        assertTrue(TapOutcome.DECLINED.report, TapOutcome.DECLINED.report.contains("declined"))
        assertTrue(TapOutcome.GUARDED.report, TapOutcome.GUARDED.report.contains("installer"))
    }
}

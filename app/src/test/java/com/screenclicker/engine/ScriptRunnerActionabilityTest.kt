package com.screenclicker.engine

import com.screenclicker.model.ClickMode
import com.screenclicker.model.PxRect
import com.screenclicker.model.Rule
import com.screenclicker.vision.Confidence
import com.screenclicker.vision.MatchResult
import com.screenclicker.vision.SearchOutcome
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptRunnerActionabilityTest {

    private val candidate = MatchResult(
        score = 0.99f,
        left = 10,
        top = 20,
        width = 30,
        height = 30,
    )

    private val ambiguous = SearchOutcome(
        best = candidate,
        runnerUp = 0.99f,
        confidence = Confidence.AMBIGUOUS,
    )

    private val definedArea = PxRect(100, 200, 140, 240)

    @Test
    fun `ambiguous image presence may trigger a valid fixed click area`() {
        val rule = Rule(clickMode = ClickMode.ON_REGION, clickRegion = definedArea)

        assertTrue(ambiguous.isActionableFor(rule))
    }

    @Test
    fun `ambiguous result cannot choose an image click location`() {
        val rule = Rule(clickMode = ClickMode.ON_IMAGE)

        assertFalse(ambiguous.isActionableFor(rule))
    }

    @Test
    fun `ambiguous result with missing or empty fixed area is not actionable`() {
        val missingArea = Rule(clickMode = ClickMode.ON_REGION, clickRegion = null)
        val emptyArea = Rule(clickMode = ClickMode.ON_REGION, clickRegion = PxRect(5, 5, 5, 25))

        assertFalse(ambiguous.isActionableFor(missingArea))
        assertFalse(ambiguous.isActionableFor(emptyArea))
    }

    @Test
    fun `sharp results use the normal threshold for either destination`() {
        val sharp = SearchOutcome(candidate, SearchOutcome.NO_RUNNER_UP, Confidence.SHARP)
        val imageRule = Rule(clickMode = ClickMode.ON_IMAGE, threshold = 0.95f)
        val areaRule = Rule(clickMode = ClickMode.ON_REGION, clickRegion = definedArea, threshold = 0.95f)

        assertTrue(sharp.isActionableFor(imageRule))
        assertTrue(sharp.isActionableFor(areaRule))
        assertFalse(sharp.isActionableFor(imageRule.copy(threshold = 1.0f)))
    }
}

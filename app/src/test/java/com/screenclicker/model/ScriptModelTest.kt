package com.screenclicker.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptModelTest {

    @Test
    fun `script defaults are a new enabled empty configuration`() {
        val script = Script()
        assertEquals("New script", script.name)
        assertTrue(script.enabled)
        assertNull(script.targetPackage)
        assertTrue(script.rules.isEmpty())
        assertNotEquals(Script().id, script.id)
    }

    @Test
    fun `rule defaults are enabled image click once per appearance`() {
        val rule = Rule()
        assertTrue(rule.enabled)
        assertEquals(0.90f, rule.threshold, 0f)
        assertEquals(ClickMode.ON_IMAGE, rule.clickMode)
        assertEquals(RegionClickCount.SINGLE, rule.regionClickCount)
        assertEquals(Cadence.ONCE_PER_APPEARANCE, rule.cadence)
        assertEquals(1_000L, rule.intervalMs)
        assertNull(rule.templateFile)
        assertTrue(!rule.hasTemplate)
        assertEquals(0, rule.priority)
        assertEquals(0, rule.alignX)
        assertEquals(0, rule.alignY)
        assertEquals(0, rule.tapAlignX)
        assertEquals(0, rule.tapAlignY)
        assertNull(rule.templateRegion)
    }

    @Test
    fun `global settings defaults and custom values are readable`() {
        val defaults = GlobalSettings()
        assertEquals(250L, defaults.defaultDelayMs)
        assertEquals(30L, defaults.defaultJitterMs)
        assertEquals(0.90f, defaults.defaultThreshold, 0f)
        assertEquals(1_000L, defaults.defaultIntervalMs)
        assertTrue(defaults.showDetections)
        assertEquals(150L, defaults.scanIntervalMs)
        assertEquals("auto", defaults.captureBackend)
        assertTrue(!defaults.controlPanelEnabled)
        val custom = defaults.copy(showDetections = false, captureBackend = "accessibility")
        assertTrue(!custom.showDetections)
        assertEquals("accessibility", custom.captureBackend)
    }

    @Test
    fun `withRule appends a new rule and replaces by id without changing order`() {
        val first = Rule(id = "one", name = "first")
        val second = Rule(id = "two", name = "second")
        val script = Script(rules = listOf(first)).withRule(second)
        assertEquals(listOf(first, second), script.rules)
        val replacement = first.copy(name = "updated")
        val updated = script.withRule(replacement)
        assertEquals(listOf(replacement, second), updated.rules)
        assertSame(replacement, updated.rules[0])
    }

    @Test
    fun `withoutRule and ruleById affect only the requested rule`() {
        val first = Rule(id = "one")
        val second = Rule(id = "two")
        val script = Script(rules = listOf(first, second))
        assertEquals(second, script.ruleById("two"))
        assertNull(script.ruleById("missing"))
        assertEquals(listOf(second), script.withoutRule("one").rules)
        assertEquals(script, script.withoutRule("missing"))
    }

    @Test
    fun `complete scripts round trip through JSON including rule geometry and enums`() {
        val rule = Rule(
            id = "rule-id",
            name = "Harvest",
            enabled = false,
            templateFile = "tpl_rule-id.png",
            searchRegion = PxRect(1, 2, 30, 40),
            alignX = -3,
            alignY = 4,
            tapAlignX = 5,
            tapAlignY = -6,
            frameWidth = 1080,
            frameHeight = 2520,
            captureBackend = "projection",
            threshold = 0.87f,
            clickMode = ClickMode.ON_REGION,
            clickRegion = PxRect(50, 60, 70, 80),
            regionClickCount = RegionClickCount.PER_OCCURRENCE,
            delayMs = 123,
            jitterMs = 17,
            cadence = Cadence.REPEAT_WHILE_VISIBLE,
            intervalMs = 777,
            priority = 9,
        )
        val script = Script(
            id = "script-id",
            name = "Farm",
            targetPackage = "com.example.game",
            enabled = false,
            rules = listOf(rule),
        )
        val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
        val encoded = json.encodeToString(script)
        val decoded = json.decodeFromString<Script>(encoded)
        assertEquals(script, decoded)
        assertEquals(ClickMode.ON_REGION, decoded.rules.single().clickMode)
        assertEquals(RegionClickCount.PER_OCCURRENCE, decoded.rules.single().regionClickCount)
        assertEquals(Cadence.REPEAT_WHILE_VISIBLE, decoded.rules.single().cadence)
    }

    @Test
    fun `unknown persisted fields are ignored for forward compatibility`() {
        val json = Json { ignoreUnknownKeys = true }
        val decoded = json.decodeFromString<Script>("""{"id":"s","name":"saved","futureField":true}""")
        assertEquals("s", decoded.id)
        assertEquals("saved", decoded.name)
    }
}

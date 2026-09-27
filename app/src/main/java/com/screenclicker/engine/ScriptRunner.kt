package com.screenclicker.engine

import android.util.Log
import com.screenclicker.capture.ScreenCapturer
import com.screenclicker.model.Cadence
import com.screenclicker.model.ClickMode
import com.screenclicker.model.PxRect
import com.screenclicker.model.Rule
import com.screenclicker.model.Script
import com.screenclicker.store.SettingsRepo
import com.screenclicker.vision.GrayImage
import com.screenclicker.vision.MatchResult
import com.screenclicker.vision.TemplateMatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.max
import kotlin.random.Random

/**
 * The scan-and-click loop.
 *
 * One frame per iteration: capture the screen (the capturer owns its own throttling),
 * match every enabled rule inside its own search region, then for each rule whose
 * cadence allows a click right now: wait delay±jitter and tap a random point inside
 * the click bounds. Rules are independent — several may fire in the same frame.
 *
 * Templates are decoded once per run (PNG decode per frame would eat the scan budget).
 *
 * The loop is a plain suspending function: the caller supplies the scope and cancels
 * it to stop. Everything Android-flavored (taps, foreground package, overlays) is
 * injected, which keeps the timing logic readable and testable.
 */
class ScriptRunner(
    private val settingsRepo: SettingsRepo,
    private val capturer: ScreenCapturer,
) {

    /** Per-rule runtime state for cadence decisions; survives across frames only. */
    private class RuleState {
        var wasPresent: Boolean = false
        var lastClickAt: Long = 0L
    }

    data class Hit(val rule: Rule, val match: MatchResult)

    suspend fun run(
        script: Script,
        templates: Map<String, GrayImage>,
        foregroundPackage: () -> String?,
        onTap: suspend (x: Int, y: Int) -> Boolean,
        onDetections: (List<MatchResult>) -> Unit = {},
        onEvent: (String) -> Unit = {},
    ) {
        val states = HashMap<String, RuleState>()
        val rng = Random(System.currentTimeMillis())
        val enabledRules = script.rules
            .filter { it.enabled && it.hasTemplate }
            .sortedByDescending { it.priority }

        if (enabledRules.isEmpty()) {
            onEvent("No enabled rules with templates — nothing to do")
            return
        }

        onEvent("Running ${script.name} (${enabledRules.size} rules, backend ${capturer.name})")
        while (currentCoroutineContext().isActive) {
            val frameStarted = System.currentTimeMillis()
            val settings = settingsRepo.load()

            // Optional app binding: pause scanning while another app is foreground.
            val target = script.targetPackage
            if (target != null && foregroundPackage() != target) {
                delay(500)
                continue
            }

            val screen: GrayImage = when (val capture = capturer.capture()) {
                is com.screenclicker.capture.CaptureResult.Success ->
                    GrayImage.fromArgb(capture.argb, capture.width, capture.height)

                is com.screenclicker.capture.CaptureResult.Failure -> {
                    onEvent("Capture failed: ${capture.reason}")
                    delay(1_500)
                    continue
                }
            }

            // Match every rule this frame.
            val matches = HashMap<String, MatchResult>()
            for (rule in enabledRules) {
                val template = templates[rule.id] ?: continue
                val region = rule.searchRegion.clampedTo(screen.width, screen.height)
                if (region.width < template.width || region.height < template.height) {
                    continue
                }
                val match = TemplateMatcher.findBest(screen, template, region, rule.threshold)
                if (match != null) matches[rule.id] = match
            }

            // Cadence decisions, then update presence tracking for every rule.
            val hits = mutableListOf<Hit>()
            val now = System.currentTimeMillis()
            for (rule in enabledRules) {
                val state = states.getOrPut(rule.id) { RuleState() }
                val present = matches.containsKey(rule.id)
                val mayClick = when (rule.cadence) {
                    Cadence.ONCE_PER_APPEARANCE -> present && !state.wasPresent
                    Cadence.REPEAT_WHILE_VISIBLE ->
                        present && now - state.lastClickAt >= rule.intervalMs
                }
                if (mayClick) {
                    state.lastClickAt = now
                    hits += Hit(rule, matches[rule.id]!!)
                }
                state.wasPresent = present
            }

            onDetections(matches.values.toList())

            // Fire clicks: each waits its own delay±jitter, then taps a random point.
            for (hit in hits) {
                val bounds = clickBounds(hit.rule, hit.match)
                val jitter = if (hit.rule.jitterMs > 0) {
                    rng.nextLong(-hit.rule.jitterMs, hit.rule.jitterMs + 1)
                } else {
                    0L
                }
                val wait = max(0L, hit.rule.delayMs + jitter)
                delay(wait)
                val (x, y) = bounds.randomPoint(rng)
                val landed = onTap(x, y)
                Log.i(
                    TAG,
                    "rule='${hit.rule.name}' score=${"%.2f".format(hit.match.score)} " +
                        "tap=($x,$y) landed=$landed",
                )
                onEvent(
                    "${hit.rule.name}: ${"%.0f".format(hit.match.score * 100)}% → tap " +
                        "($x,$y)${if (landed) "" else " FAILED"}",
                )
            }

            // Hold the scan cadence.
            val elapsed = System.currentTimeMillis() - frameStarted
            delay((settings.scanIntervalMs - elapsed).coerceAtLeast(0))
        }
        onEvent("Stopped")
    }

    /** The bounds a tap may land in for this rule's hit. */
    private fun clickBounds(rule: Rule, match: MatchResult): PxRect = when (rule.clickMode) {
        ClickMode.ON_IMAGE ->
            PxRect(match.left, match.top, match.left + match.width, match.top + match.height)

        ClickMode.ON_REGION -> rule.clickRegion ?: PxRect(
            match.left,
            match.top,
            match.left + match.width,
            match.top + match.height,
        )
    }

    private companion object {
        const val TAG = "ScriptRunner"
    }
}
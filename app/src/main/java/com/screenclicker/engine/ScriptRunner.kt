package com.screenclicker.engine

import android.os.SystemClock
import android.util.Log
import com.screenclicker.capture.CaptureResult
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

    /**
     * Per-rule runtime state for cadence decisions; survives across frames only.
     *
     * Times are [SystemClock.elapsedRealtime], not wall clock: an NTP correction or a
     * timezone jump mid-run would otherwise be read as "the interval elapsed" and fire
     * an extra click (or block one for hours).
     */
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
            val frameStarted = SystemClock.elapsedRealtime()
            val settings = settingsRepo.load()

            // Optional app binding: pause scanning while another app is foreground.
            val target = script.targetPackage
            if (target != null && foregroundPackage() != target) {
                delay(500)
                continue
            }

            val captureStarted = System.currentTimeMillis()
            val capture = capturer.capture()
            val captureMs = System.currentTimeMillis() - captureStarted
            val frame = when (capture) {
                is CaptureResult.Success -> capture

                is CaptureResult.Failure -> {
                    onEvent("Capture failed: ${capture.reason}")
                    delay(1_500)
                    continue
                }
            }

            // Only the union of the rules' search regions is converted to grayscale.
            // Converting the whole screen every frame (plus a same-sized grayscale
            // array) was ~23MB of garbage per scan and the main source of jank.
            val regions = LinkedHashMap<String, PxRect>()
            for (rule in enabledRules) {
                val template = templates[rule.id] ?: continue
                // The editor's measured alignment moves the drawn region onto the same
                // place in a screenshot; zero on devices where they already agree.
                val region = rule.searchRegion
                    .translated(rule.alignX, rule.alignY)
                    .clampedTo(frame.width, frame.height)
                if (region.width < template.width || region.height < template.height) continue
                regions[rule.id] = region
            }
            val union = regions.values.reduceOrNull { a, b -> a.union(b) }
            val matches = HashMap<String, MatchResult>()
            var grayMs = 0L
            var matchMs = 0L
            if (union != null) {
                val grayStarted = System.currentTimeMillis()
                val scan = GrayImage.fromArgbRegion(frame.argb, frame.width, frame.height, union)
                grayMs = System.currentTimeMillis() - grayStarted

                for (rule in enabledRules) {
                    val region = regions[rule.id] ?: continue
                    val template = templates[rule.id] ?: continue
                    // The cropped image has its own origin; search in local coords and
                    // translate the hit back to screen coords, where everything else
                    // (taps, highlights, click regions) lives.
                    val local = PxRect(
                        region.left - union.left,
                        region.top - union.top,
                        region.right - union.left,
                        region.bottom - union.top,
                    )
                    val matchStarted = System.currentTimeMillis()
                    val match = TemplateMatcher.findBest(scan, template, local, rule.threshold)
                    matchMs += System.currentTimeMillis() - matchStarted
                    if (match != null) {
                        matches[rule.id] = match.copy(
                            left = match.left + union.left,
                            top = match.top + union.top,
                        )
                    }
                }
            }

            // Periodic timing so the user can see where the scan budget goes.
            if (SystemClock.elapsedRealtime() - lastStatsAt >= STATS_INTERVAL_MS) {
                lastStatsAt = SystemClock.elapsedRealtime()
                val scanMs = captureMs + grayMs + matchMs
                onEvent(
                    "scan ${scanMs}ms — capture $captureMs, gray $grayMs, match $matchMs " +
                        "(${union?.let { "region ${it.width}x${it.height}" } ?: "no search region"})",
                )
            }

            // Cadence decisions, then update presence tracking for every rule.
            val hits = mutableListOf<Hit>()
            val now = SystemClock.elapsedRealtime()
            for (rule in enabledRules) {
                val state = states.getOrPut(rule.id) { RuleState() }
                val present = matches.containsKey(rule.id)
                val mayClick = when (rule.cadence) {
                    Cadence.ONCE_PER_APPEARANCE -> present && !state.wasPresent
                    Cadence.REPEAT_WHILE_VISIBLE ->
                        present && now - state.lastClickAt >= rule.intervalMs
                }
                // lastClickAt is stamped only once a tap actually lands, below: a
                // refused gesture should not burn the rule's whole interval.
                if (mayClick) hits += Hit(rule, matches[rule.id]!!)
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
                if (landed) states[hit.rule.id]?.lastClickAt = SystemClock.elapsedRealtime()
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
            val elapsed = SystemClock.elapsedRealtime() - frameStarted
            delay((settings.scanIntervalMs - elapsed).coerceAtLeast(0))
        }
        onEvent("Stopped")
    }

    /**
     * The bounds a tap may land in for this rule's hit.
     *
     * Two coordinate spaces meet here. Matching runs in *screenshot* space, so the search
     * region carries the editor's measured [Rule.alignX]. Taps are injected and highlights
     * drawn in *display* space, whose correction is [Rule.tapAlignX]. On any device where a
     * screenshot is the display pixel for pixel the two are equal; where they are not (a
     * screenshot smaller than the display), using one for both would put every tap out by
     * the difference.
     */
    private fun clickBounds(rule: Rule, match: MatchResult): PxRect {
        val matchBounds = PxRect(
            match.left,
            match.top,
            match.left + match.width,
            match.top + match.height,
        )
        val configured = rule.clickRegion
        return when {
            // A match is in screenshot space: bring it into display space for the gesture.
            rule.clickMode != ClickMode.ON_REGION || configured == null ||
                configured.width <= 0 || configured.height <= 0 -> matchBounds.translated(
                rule.tapAlignX - rule.alignX,
                rule.tapAlignY - rule.alignY,
            )

            // A configured region is in the editor's model space, like the search region.
            else -> configured.translated(rule.tapAlignX, rule.tapAlignY)
        }
    }

    /** Throttles the periodic "where did the scan budget go" status lines. */
    private var lastStatsAt = 0L

    private companion object {
        const val TAG = "ScriptRunner"
        const val STATS_INTERVAL_MS = 2_000L
    }
}
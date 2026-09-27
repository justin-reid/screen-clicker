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
import com.screenclicker.vision.Confidence
import com.screenclicker.vision.MatchResult
import com.screenclicker.vision.SearchOutcome
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
        val rulesById = enabledRules.associateBy { it.id }
        var reportedFrame = false
        var reportedSpaceMismatch = false
        var reportedSkips = false
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
            val mismatched = mutableListOf<Rule>()
            // Rules that cannot be matched at all this frame, and why: "no search region" in
            // the periodic line cannot be told apart from a region drawn in the wrong place.
            val skipped = mutableListOf<String>()
            for (rule in enabledRules) {
                val template = templates[rule.id]
                if (template == null) {
                    skipped += "'${rule.name}' template could not be read"
                    continue
                }
                // A rule is only matchable while the capture space matches the one its template
                // was cropped in. Getting this wrong is not a near miss: the template's pixels
                // can never line up, so the rule either never fires or fires on something that
                // merely happens to correlate.
                if (rule.frameWidth > 0 && rule.frameHeight > 0 &&
                    (rule.frameWidth != frame.width || rule.frameHeight != frame.height)
                ) {
                    mismatched += rule
                    continue
                }
                // The editor's measured alignment moves the drawn region onto the same
                // place in a screenshot; zero on devices where they already agree.
                val region = rule.searchRegion
                    .translated(rule.alignX, rule.alignY)
                    .clampedTo(frame.width, frame.height)
                if (region.width < template.width || region.height < template.height) {
                    skipped += "'${rule.name}' search area ${region.width}x${region.height} is " +
                        "smaller than its template " +
                        "${template.width}x${template.height}"
                    continue
                }
                regions[rule.id] = region
            }
            if (skipped.isNotEmpty() && !reportedSkips) {
                reportedSkips = true
                onEvent("Not matching: ${skipped.joinToString("; ")}")
            }
            if (mismatched.isNotEmpty() && !reportedSpaceMismatch) {
                reportedSpaceMismatch = true
                val rule = mismatched.first()
                onEvent(
                    "Capture space mismatch: '${rule.name}' was captured at " +
                        "${rule.frameWidth}x${rule.frameHeight}" +
                        (if (rule.captureBackend.isEmpty()) "" else " (${rule.captureBackend})") +
                        " but captures are now ${frame.width}x${frame.height}. Not matching it — " +
                        "re-capture its template with the current capture backend.",
                )
            }
            if (!reportedFrame && regions.isNotEmpty()) {
                reportedFrame = true
                onEvent("frame ${frame.width}x${frame.height} via ${capturer.name}")
                // One line per rule: the alignment is what has to be checked against a
                // mismatch, and a single long line gets clipped in the floating panel.
                for ((ruleId, region) in regions) {
                    val rule = rulesById.getValue(ruleId)
                    val template = templates[ruleId]!!
                    onEvent(
                        "'${rule.name}': region ${region.width}x${region.height} at " +
                            "(${region.left},${region.top}), template ${template.width}x" +
                            "${template.height}, align (${rule.alignX},${rule.alignY}), " +
                            "highlight offset " +
                            "(${rule.tapAlignX - rule.alignX},${rule.tapAlignY - rule.alignY}), " +
                            "drawn at (${rule.searchRegion.left},${rule.searchRegion.top})",
                    )
                }
            }
            val union = regions.values.reduceOrNull { a, b -> a.union(b) }
            val matches = HashMap<String, MatchResult>()
            // Every rule's search this frame, kept whole: a bare score cannot tell a find from a
            // tie between two bland patches, and that distinction is what decides whether a
            // highlight is drawn and where a tap goes.
            val outcomes = LinkedHashMap<String, SearchOutcome>()
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
                    // No threshold here: the scan is identical either way, so this reports the
                    // score that was always being computed and the rule's own threshold is
                    // applied below.
                    val outcome = TemplateMatcher.search(scan, template, local)
                    matchMs += System.currentTimeMillis() - matchStarted
                    outcomes[rule.id] = outcome
                    val best = outcome.best
                    if (best != null && outcome.confidence == Confidence.SHARP &&
                        best.score >= rule.threshold
                    ) {
                        matches[rule.id] = best.copy(
                            left = best.left + union.left,
                            top = best.top + union.top,
                        )
                    }
                }
            }

            // Periodic timing so the user can see where the scan budget goes.
            if (SystemClock.elapsedRealtime() - lastStatsAt >= STATS_INTERVAL_MS) {
                lastStatsAt = SystemClock.elapsedRealtime()
                val scanMs = captureMs + grayMs + matchMs
                onEvent(
                    "scan ${scanMs}ms — capture $captureMs, gray $grayMs, match $matchMs, " +
                        "cadence ${settings.scanIntervalMs}ms " +
                        "(frame ${frame.width}x${frame.height}, " +
                        (union?.let { "region ${it.width}x${it.height}" } ?: "no search region") +
                        ")" +
                        logOutcomes(outcomes, rulesById),
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

            // The highlight window draws in display space and matches are in screenshot
            // space (see clickBounds); the two are the same on most devices, and this shows
            // a shifted box when they are not — rather than a box that silently lies.
            onDetections(
                matches.map { (ruleId, match) ->
                    val rule = rulesById.getValue(ruleId)
                    match.translated(rule.tapAlignX - rule.alignX, rule.tapAlignY - rule.alignY)
                },
            )

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

    /**
     * " name 87%" per rule, in rule order; ids keep same-named rules from collapsing.
     *
     * A score that could not be acted on says why instead of showing a number that looks like
     * a find: a template with nothing in it, a frame where nothing scored, or two candidates so
     * close that which one is *the* image cannot be told. The last case is what a highlight
     * drawn beside the image instead of on it looks like, and it is not a threshold problem.
     */
    private fun logOutcomes(
        outcomes: Map<String, SearchOutcome>,
        rules: Map<String, Rule>,
    ): String = outcomes.entries.joinToString("") { (ruleId, outcome) ->
        val name = rules[ruleId]?.name ?: ruleId
        val best = outcome.best
        val second = outcome.runnerUp
        when {
            outcome.confidence == Confidence.NO_CONTRAST -> " $name: template has no contrast"
            best == null -> " $name: nothing found"
            outcome.confidence == Confidence.AMBIGUOUS ->
                " $name ${percent(best.score)} (2nd ${percent(second)}, ambiguous)"
            second >= best.score - 0.10f -> " $name ${percent(best.score)} (2nd ${percent(second)})"
            else -> " $name ${percent(best.score)}"
        }
    }

    private fun percent(score: Float): String = "${(score * 100).toInt()}%"

    /** Same rect in the other coordinate space; a no-op where the spaces coincide. */
    private fun MatchResult.translated(dx: Int, dy: Int): MatchResult =
        if (dx == 0 && dy == 0) this else copy(left = left + dx, top = top + dy)

    /** Throttles the periodic "where did the scan budget go" status lines. */
    private var lastStatsAt = 0L

    private companion object {
        const val TAG = "ScriptRunner"
        const val STATS_INTERVAL_MS = 2_000L
    }
}
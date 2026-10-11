package com.screenclicker.engine

import android.os.SystemClock
import android.util.Log
import com.screenclicker.capture.CaptureResult
import com.screenclicker.capture.ScreenCapturer
import com.screenclicker.model.Cadence
import com.screenclicker.model.ClickMode
import com.screenclicker.model.PxRect
import com.screenclicker.model.RegionClickCount
import com.screenclicker.model.Rule
import com.screenclicker.model.Script
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

internal fun Rule.hasDefinedClickArea(): Boolean =
    clickMode == ClickMode.ON_REGION &&
        clickRegion?.let { it.width > 0 && it.height > 0 } == true

/**
 * True when this rule's defined area should be pressed once per appearance rather than once
 * per matched occurrence. Detection and highlights are unaffected either way; this only
 * decides how many finds turn into taps on the one shared area.
 */
internal fun Rule.pressesRegionOnce(): Boolean =
    hasDefinedClickArea() && regionClickCount == RegionClickCount.SINGLE

/**
 * The scan-and-click loop.
 *
 * One frame per iteration: capture the screen (the capturer owns its own throttling),
 * match every enabled rule inside its own search region, then for each rule whose
 * cadence allows a click right now: wait delay±jitter and tap a random point inside
 * the click bounds. Rules are independent — several may fire in the same frame.
 *
 * A rule whose destination is one shared area can also be told to press it once per
 * appearance rather than once per find (see [pressesRegionOnce]); then it behaves as a
 * single switch even while several occurrences of its template are on screen.
 *
 * Templates are decoded once per run (PNG decode per frame would eat the scan budget).
 *
 * The loop is a plain suspending function: the caller supplies the scope and cancels
 * it to stop. Everything Android-flavored (taps, foreground package, overlays) is
 * injected, which keeps the timing logic readable and testable.
 */
class ScriptRunner(
    private val loadSettings: () -> com.screenclicker.model.GlobalSettings,
    private val capturer: ScreenCapturer,
    private val elapsedRealtime: () -> Long = { SystemClock.elapsedRealtime() },
    private val wait: suspend (Long) -> Unit = { delay(it) },
    private val writeLog: (String) -> Unit = { Log.i(TAG, it) },
) {
    /**
     * Per-rule runtime state for cadence decisions; survives across frames only.
     *
     * Times are [SystemClock.elapsedRealtime], not wall clock: an NTP correction or a
     * timezone jump mid-run would otherwise be read as "the interval elapsed" and fire
     * an extra click (or block one for hours).
     */
    private class RuleState {
        var previousMatches: List<MatchResult> = emptyList()
        val lastClickAtByLocation = mutableMapOf<Pair<Int, Int>, Long>()
    }

    data class Hit(val rule: Rule, val match: MatchResult)

    suspend fun run(
        script: Script,
        templates: Map<String, GrayImage>,
        foregroundPackage: () -> String?,
        onTap: suspend (x: Int, y: Int) -> TapOutcome,
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
        // Keep the last gesture result in the periodic panel status. A one-shot tap event
        // was overwritten by the next scan line before users could read whether a tap ran.
        var lastTapOutcome: String? = null
        // How many gestures this run has tried to inject. A tally that stays at zero while
        // the status line reports hits is the difference between a cadence that is not
        // firing and a tap that is never reaching the system.
        var tapAttempts = 0
        while (currentCoroutineContext().isActive) {
            val frameStarted = elapsedRealtime()
            val settings = loadSettings()

            // Optional app binding: pause scanning while another app is foreground.
            val target = script.targetPackage
            if (target != null && foregroundPackage() != target) {
                wait(500)
                continue
            }

            val captureStarted = System.currentTimeMillis()
            val capture = capturer.capture()
            val captureMs = System.currentTimeMillis() - captureStarted
            val frame = when (capture) {
                is CaptureResult.Success -> capture

                is CaptureResult.Failure -> {
                    onEvent("Capture failed: ${capture.reason}")
                    wait(1_500)
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
            val matches = HashMap<String, List<MatchResult>>()
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
                    // The threshold is applied by the matcher to every separated occurrence.
                    // Repeated grid items are all real hits; neither a score tie nor a fixed
                    // click-area destination collapses them into one arbitrary location.
                    val outcome = TemplateMatcher.search(scan, template, local, rule.threshold)
                    matchMs += System.currentTimeMillis() - matchStarted
                    outcomes[rule.id] = outcome
                    if (outcome.matches.isNotEmpty()) {
                        matches[rule.id] = outcome.matches.map { match ->
                            match.copy(left = match.left + union.left, top = match.top + union.top)
                        }
                    }
                }
            }

            // Cadence decisions, then update presence tracking for every rule.
            val hits = mutableListOf<Hit>()
            val now = elapsedRealtime()
            for (rule in enabledRules) {
                val state = states.getOrPut(rule.id) { RuleState() }
                // A rule that presses one shared area once per appearance is a single switch,
                // not one switch per occurrence: collapse the frame's finds to one tracked
                // location so cadence, repeat interval, and gesture retry all key on the rule
                // as a whole. Detection and highlights still see every occurrence.
                val present = if (rule.pressesRegionOnce()) {
                    matches[rule.id].orEmpty().take(1).map { it.atTrackedGroupLocation() }
                } else {
                    matches[rule.id].orEmpty()
                }
                when (rule.cadence) {
                    Cadence.ONCE_PER_APPEARANCE -> {
                        for (match in present) {
                            if (state.previousMatches.none { sameOccurrence(it, match) }) {
                                hits += Hit(rule, match)
                            }
                        }
                    }
                    Cadence.REPEAT_WHILE_VISIBLE -> {
                        for (match in present) {
                            val lastClick = state.lastClickAtByLocation.entries
                                .firstOrNull { (location, _) -> sameOccurrence(location, match) }
                                ?.value
                            if (lastClick == null || now - lastClick >= rule.intervalMs) {
                                hits += Hit(rule, match)
                            }
                        }
                        val retained = present.mapNotNull { match ->
                            state.lastClickAtByLocation.entries
                                .firstOrNull { (location, _) -> sameOccurrence(location, match) }
                                ?.let { (match.left to match.top) to it.value }
                        }.toMap()
                        state.lastClickAtByLocation.clear()
                        state.lastClickAtByLocation.putAll(retained)
                    }
                }
                state.previousMatches = present
            }

            // The highlight window draws in display space and matches are in screenshot
            // space (see clickBounds); the two are the same on most devices, and this shows
            // a shifted box when they are not — rather than a box that silently lies.
            onDetections(
                matches.flatMap { (ruleId, occurrences) ->
                    val rule = rulesById.getValue(ruleId)
                    occurrences.map { match ->
                        match.translated(rule.tapAlignX - rule.alignX, rule.tapAlignY - rule.alignY)
                    }
                },
            )

            // Say what the scan decided to do before doing it. The panel shows only the
            // latest event, so a run that stalls between deciding and tapping — or that
            // never decides at all — otherwise looks exactly like one that is finding
            // nothing, which is how "it matched 36 and clicked nothing" stayed invisible.
            if (hits.isNotEmpty()) {
                val queued = hits.groupingBy { it.rule.name }.eachCount()
                    .entries.joinToString { (name, count) -> "$name ×$count" }
                onEvent("Taps queued: $queued")

                // One reaction per scan, not one reaction per occurrence. delayMs is the
                // time between seeing something and acting on it, and a frame full of
                // matches is one thing seen. Paying it per find made a run's reaction time
                // scale with how much it found: a grid of twenty cells spent twenty delays
                // before the loop could so much as look at the screen again, which is
                // exactly the "it takes five or six seconds" report. The slowest firing
                // rule sets the frame's reaction; the other finds go out with it.
                val reaction = hits.maxByOrNull { it.rule.delayMs }!!
                val jitterSpan = reaction.rule.jitterMs
                val jitter = if (jitterSpan > 0) {
                    rng.nextLong(-jitterSpan, jitterSpan + 1)
                } else {
                    0L
                }
                wait(max(0L, reaction.rule.delayMs + jitter))
            }

            // Fire the queued taps back to back. The wait above was the frame's reaction;
            // what is left per tap is only the system's own gesture round trip.
            val burstStarted = elapsedRealtime()
            for (hit in hits) {
                val bounds = clickBounds(hit.rule, hit.match)
                val (x, y) = bounds.randomPoint(rng)
                // Emitted before the injection, not after: if the system never reports the
                // gesture back, this line is the evidence of where the run stopped.
                tapAttempts++
                onEvent("${hit.rule.name}: injecting tap at ($x,$y) — attempt $tapAttempts")
                val reply = onTap(x, y)
                val landed = reply.acted
                val state = states[hit.rule.id]
                if (landed && hit.rule.cadence == Cadence.REPEAT_WHILE_VISIBLE) {
                    state?.lastClickAtByLocation?.set(hit.match.left to hit.match.top, elapsedRealtime())
                } else if (!landed && hit.rule.cadence == Cadence.ONCE_PER_APPEARANCE) {
                    // A refused/cancelled gesture did not act on this occurrence; leave its
                    // edge unconsumed so it retries without re-clicking other found instances.
                    state?.previousMatches = state.previousMatches.filterNot { sameOccurrence(it, hit.match) }
                }
                val destination = if (hit.rule.hasDefinedClickArea()) "area" else "match"
                val tapOutcome = "${hit.rule.name}: $destination bounds=$bounds " +
                    "tap=($x,$y), ${reply.report}"
                lastTapOutcome = tapOutcome
                writeLog(
                    "rule='${hit.rule.name}' score=${"%.2f".format(hit.match.score)} " +
                        "destination=$destination bounds=$bounds tap=($x,$y) " +
                        "reply=${reply.name} completed=$landed",
                )
                onEvent(tapOutcome)
            }
            // What the taps themselves cost, apart from the reaction above. The scan line
            // used to add up to "capture + gray + match" and call that the scan, which hid
            // a tap burst that could be several times longer than the whole scan.
            val tapBurstMs = if (hits.isEmpty()) 0L else elapsedRealtime() - burstStarted

            // Periodic status, now that this scan's taps have run: the hit count and the
            // run-wide tap tally are what separate "found nothing" from "found plenty and
            // never tapped", and the last tap is this scan's rather than a stale one.
            if (elapsedRealtime() - lastStatsAt >= STATS_INTERVAL_MS) {
                lastStatsAt = elapsedRealtime()
                val scanMs = captureMs + grayMs + matchMs
                val highlightOffsets = enabledRules.joinToString { rule ->
                    "${rule.name}(${rule.tapAlignX - rule.alignX}," +
                        "${rule.tapAlignY - rule.alignY})"
                }
                onEvent(
                    "scan ${scanMs}ms — capture $captureMs, gray $grayMs, match $matchMs, " +
                        "taps ${tapBurstMs}ms, cadence ${settings.scanIntervalMs}ms " +
                        "(frame ${frame.width}x${frame.height}, " +
                        (union?.let { "region ${it.width}x${it.height}" } ?: "no search region") +
                        ")" +
                        "\nhits ${hits.size}, taps this run $tapAttempts, " +
                        "last: ${lastTapOutcome ?: "none yet"}" +
                        "\nHighlight Δ: $highlightOffsets" +
                        "\n" + logOutcomes(outcomes, rulesById),
                )
            }

            // Hold the scan cadence.
            val elapsed = elapsedRealtime() - frameStarted
            wait((settings.scanIntervalMs - elapsed).coerceAtLeast(0))
        }
        onEvent("Stopped")
    }

    /**
     * Occurrence identity is the match location with the same separation used by the matcher.
     */
    private fun sameOccurrence(a: MatchResult, b: MatchResult): Boolean {
        val separation = max(8, minOf(a.width, a.height, b.width, b.height) / 2)
        return kotlin.math.abs(a.left - b.left) < separation &&
            kotlin.math.abs(a.top - b.top) < separation
    }

    /**
     * The one location a "press the area once" rule keys its cadence on, in place of the
     * per-occurrence locations it found. The position itself is unused — the tap goes to the
     * configured area either way — it only has to be the same every frame.
     */
    private fun MatchResult.atTrackedGroupLocation(): MatchResult =
        if (left == 0 && top == 0) this else copy(left = 0, top = 0)

    private fun sameOccurrence(location: Pair<Int, Int>, match: MatchResult): Boolean =
        sameOccurrence(
            MatchResult(0f, location.first, location.second, match.width, match.height),
            match,
        )

    /**
     * Tap bounds are either this occurrence in display coordinates or the rule's shared
     * configured click area. The match and configured area use different alignment offsets.
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
            !rule.hasDefinedClickArea() -> matchBounds.translated(
                rule.tapAlignX - rule.alignX,
                rule.tapAlignY - rule.alignY,
            )

            // A configured region is in the editor's model space, like the search region.
            else -> checkNotNull(configured).translated(rule.tapAlignX, rule.tapAlignY)
        }
    }

    /**
     * " name 87%" per rule, in rule order; ids keep same-named rules from collapsing.
     *
     * Scores and runner-up are diagnostics; click/highlight behavior consumes every match
     * at or above the rule threshold. Multiple occurrences are expected.
     */
    private fun logOutcomes(
        outcomes: Map<String, SearchOutcome>,
        rules: Map<String, Rule>,
    ): String = outcomes.entries.joinToString("") { (ruleId, outcome) ->
        val name = rules[ruleId]?.name ?: ruleId
        val best = outcome.best
        val second = outcome.runnerUp
        // Which evidence carried the find: "edge" means the high-pass pass won, which is
        // what a target mid-fade looks like — worth knowing before touching the threshold.
        val via = if (best?.viaHighPass == true) " (edge)" else ""
        when {
            outcome.confidence == Confidence.NO_CONTRAST -> " $name: template has no contrast"
            best == null -> " $name: nothing found"
            outcome.confidence == Confidence.AMBIGUOUS ->
                " $name ${percent(best.score)} (2nd ${percent(second)}, close scores; " +
                    "${outcome.matches.size} hit(s))$via"
            outcome.matches.size > 1 ->
                " $name ${percent(best.score)} (${outcome.matches.size} hits)$via"
            second >= best.score - 0.10f -> " $name ${percent(best.score)} (2nd ${percent(second)})$via"
            else -> " $name ${percent(best.score)}$via"
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
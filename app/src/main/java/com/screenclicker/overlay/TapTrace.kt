package com.screenclicker.overlay

import com.screenclicker.model.PxRect

/**
 * One injected tap, as the run overlay draws it.
 *
 * @param bounds the region the point was drawn from — the rule's configured click area, or
 *   the matched image's own bounds when the destination is the find. Drawn under the dot so a
 *   scatter of dots can be read against the area they came from, which is the whole point:
 *   "inside the area" is a claim about a distribution, and one dot cannot show a distribution.
 * @param acted whether the system took the gesture (see
 *   [com.screenclicker.engine.TapOutcome]). A refused tap still gets a marker, drawn
 *   differently — a run whose every gesture is declined must not look like a run that is
 *   tapping, which is exactly how the missing `canPerformGestures` stayed invisible for
 *   several rounds.
 */
data class TapMark(
    val x: Int,
    val y: Int,
    val bounds: PxRect,
    val acted: Boolean,
    val at: Long,
)

/**
 * The rolling record behind the "show taps" layer.
 *
 * A plain class with the clock injected rather than a view method, so the expiry and fade
 * rules are testable on the JVM; the view is the only Android part ([RunOverlayView]).
 *
 * The record is bounded ([limit]) *and* self-expiring ([ttlMs]) on purpose. A run can inject
 * thousands of taps: markers that never went away would grow the list without bound and turn
 * the screen into a smear, and markers that vanished instantly would each say nothing —
 * several taps have to be on screen at once for the spread to be legible against the centre
 * the user suspects every tap of using.
 */
class TapTrace(
    private val limit: Int = DEFAULT_LIMIT,
    private val ttlMs: Long = DEFAULT_TTL_MS,
) {
    private val marks = ArrayDeque<TapMark>()

    fun add(mark: TapMark) {
        marks.addLast(mark)
        while (marks.size > limit) marks.removeFirst()
    }

    fun clear() = marks.clear()

    /**
     * The marks still worth drawing at [now], oldest first.
     *
     * Expired marks are dropped here rather than by a timer of their own, so the record cannot
     * hold taps that nobody can see any more.
     */
    fun live(now: Long): List<TapMark> {
        while (marks.isNotEmpty() && now - marks.first().at >= ttlMs) marks.removeFirst()
        return marks.toList()
    }

    /** 1.0 when the tap has just landed, 0.0 at expiry — the marker's alpha. */
    fun fade(mark: TapMark, now: Long): Float =
        (1f - (now - mark.at).toFloat() / ttlMs).coerceIn(0f, 1f)

    companion object {
        /**
         * Enough to read a spread across a click area at a glance, few enough that a run
         * firing every scan cannot bury the screen.
         */
        const val DEFAULT_LIMIT = 60

        /**
         * Long enough that several taps are visible together — one marker alone answers
         * nothing — short enough that they clear themselves with no action from the user.
         */
        const val DEFAULT_TTL_MS = 2_500L
    }
}

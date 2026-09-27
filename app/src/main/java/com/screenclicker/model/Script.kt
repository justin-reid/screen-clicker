package com.screenclicker.model

import kotlinx.serialization.Serializable
import java.util.UUID

/** Where the tap goes when a rule matches. */
@Serializable
enum class ClickMode {
    /** On the matched image itself, at a random point within its bounds. */
    ON_IMAGE,

    /** On a separately configured region, at a random point within its bounds. */
    ON_REGION,
}

/**
 * When a matching rule may click again.
 *
 * ONCE_PER_APPEARANCE is the default: the rule fires when the image transitions from
 * absent to present, and stays quiet while it remains on screen — for one-shot dialogs
 * that must not be double-clicked. REPEAT_WHILE_VISIBLE fires on an interval for as
 * long as the image stays detected — classic auto-clicker spam.
 */
@Serializable
enum class Cadence {
    ONCE_PER_APPEARANCE,
    REPEAT_WHILE_VISIBLE,
}

/**
 * One detection-and-click rule: look for [templateFile] inside [searchRegion] shifted by
 * [alignX]/[alignY], and when the match scores at least [threshold], tap (after [delayMs]
 * ± [jitterMs]) either the match or [clickRegion].
 */
@Serializable
data class Rule(
    val id: String = newId(),
    val name: String = "",
    val enabled: Boolean = true,
    /** PNG file name (not path) inside the templates directory; null until captured. */
    val templateFile: String? = null,
    val searchRegion: PxRect = PxRect(0, 0, 0, 0),
    /**
     * Last on-screen capture box, so re-capturing a template starts from the same place.
     * Editor-only state: the engine never reads it.
     */
    val templateRegion: PxRect? = null,
    /**
     * Alignment correction measured by the rule editor, applied to [searchRegion] and
     * [clickRegion] when the engine uses them.
     *
     * Overlay window coordinates and screenshot coordinates do not always share an origin
     * (see the overlay's ProbeMarker), so the regions the user draws live in the editor's
     * model space and can sit a constant offset away from the same place in a screenshot.
     * The editor measures that offset and records it here; zero means "no correction",
     * which is the case on most devices.
     */
    val alignX: Int = 0,
    val alignY: Int = 0,
    /**
     * The same correction measured in *display* coordinates, which is the space taps are
     * injected in and detection highlights are drawn in — as opposed to [alignX]/[alignY],
     * which are measured in the screenshot's coordinates and used for the search region.
     *
     * They are equal on any device where a screenshot is the display pixel for pixel. They
     * differ when a screenshot is not the size of the display (foldables), and in that case
     * a tap placed with [alignX] alone would land at the wrong place.
     */
    val tapAlignX: Int = 0,
    val tapAlignY: Int = 0,
    /**
     * Size of the capture the template was cropped from and the alignments were measured
     * against, and which backend produced it.
     *
     * Capture backends are not interchangeable: a mediaProjection mirror can come out at a
     * different size than an accessibility screenshot, especially on a foldable, and then a
     * template cropped in one space can never match inside a region placed in the other —
     * matching would fail silently or fire on something else. The runner compares the frame
     * it gets against these and refuses to match rather than clicking the wrong thing.
     *
     * Zero means "unknown" (rules saved before this was recorded): matching proceeds as it
     * did before.
     */
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
    val captureBackend: String = "",
    /** Match score (0..1) required to trigger. */
    val threshold: Float = 0.90f,
    val clickMode: ClickMode = ClickMode.ON_IMAGE,
    val clickRegion: PxRect? = null,
    /** Wait between detection and tap; the reaction-time calibration sets the default. */
    val delayMs: Long = 0,
    /** Random fuzz applied to [delayMs]: effective delay = delay ± random(jitter). */
    val jitterMs: Long = 0,
    val cadence: Cadence = Cadence.ONCE_PER_APPEARANCE,
    /** For REPEAT_WHILE_VISIBLE: minimum time between clicks on this rule. */
    val intervalMs: Long = 1_000,
    /** Higher priority rules are evaluated first; first match wins the frame. */
    val priority: Int = 0,
) {
    val hasTemplate: Boolean get() = templateFile != null
}

/**
 * A named, ordered set of rules, optionally bound to one app ([targetPackage]) so the
 * script only runs while that app is in the foreground.
 */
@Serializable
data class Script(
    val id: String = newId(),
    val name: String = "New script",
    val targetPackage: String? = null,
    val enabled: Boolean = true,
    val rules: List<Rule> = emptyList(),
) {
    fun ruleById(ruleId: String): Rule? = rules.firstOrNull { it.id == ruleId }

    fun withRule(rule: Rule): Script {
        val index = rules.indexOfFirst { it.id == rule.id }
        return if (index >= 0) {
            copy(rules = rules.toMutableList().apply { set(index, rule) })
        } else {
            copy(rules = rules + rule)
        }
    }

    fun withoutRule(ruleId: String): Script = copy(rules = rules.filterNot { it.id == ruleId })
}

private fun newId(): String = UUID.randomUUID().toString()

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
 * One detection-and-click rule: look for [templateFile] inside [searchRegion], and when
 * the match scores at least [threshold], tap (after [delayMs] ± [jitterMs]) either the
 * match or [clickRegion].
 */
@Serializable
data class Rule(
    val id: String = newId(),
    val name: String = "",
    val enabled: Boolean = true,
    /** PNG file name (not path) inside the templates directory; null until captured. */
    val templateFile: String? = null,
    val searchRegion: PxRect = PxRect(0, 0, 0, 0),
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

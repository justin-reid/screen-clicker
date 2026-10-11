package com.screenclicker.engine

/**
 * How one injected tap ended, as far as the system told us.
 *
 * These stay apart because they need different fixes, and because collapsing them into a
 * boolean is what hid a total failure of the tap path for several rounds: "the service was
 * never allowed to inject gestures at all", "something interrupted this one", and "the
 * system took it and never answered" all rendered as "gesture cancelled".
 */
enum class TapOutcome {
    /** The system reported the gesture delivered to the screen. */
    DELIVERED,

    /** Our own safety guard: the package installer was on screen, so nothing was injected. */
    GUARDED,

    /**
     * The system declined the gesture outright — `dispatchGesture` returned false. This is
     * not transient: it means the service is not permitted to inject gestures at all (see
     * `android:canPerformGestures` in the service config) or is no longer connected.
     */
    DECLINED,

    /** The system took the gesture and then cancelled it (something interrupted it). */
    CANCELLED,

    /** The system took the gesture and never reported it back within the timeout. */
    UNANSWERED,
    ;

    /** True when the gesture can be assumed to have reached the screen. */
    val acted: Boolean get() = this == DELIVERED

    /** The phrase the runner and the settings screen show for this outcome. */
    val report: String
        get() = when (this) {
            DELIVERED -> "gesture delivered"
            GUARDED -> "refused while the installer is on screen"
            DECLINED -> "gesture declined — the system would not take it"
            CANCELLED -> "gesture cancelled"
            UNANSWERED -> "no reply from the system within the timeout"
        }
}

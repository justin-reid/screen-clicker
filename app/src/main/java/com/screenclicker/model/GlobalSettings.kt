package com.screenclicker.model

/**
 * Global (cross-script) settings. Stored in SharedPreferences rather than JSON because
 * the engine reads these synchronously on every scan iteration.
 */
data class GlobalSettings(
    /** Applied to new rules; the reaction-time calibration writes this. */
    val defaultDelayMs: Long = 250,
    /** Applied to new rules. */
    val defaultJitterMs: Long = 30,
    /** Applied to new rules. */
    val defaultThreshold: Float = 0.90f,
    /** Applied to new rules with REPEAT_WHILE_VISIBLE cadence. */
    val defaultIntervalMs: Long = 1_000,
    /** Highlight matched regions live while a script runs (M6). */
    val showDetections: Boolean = true,
    /**
     * Draw a marker where each tap lands, with the area it was drawn from (M10).
     *
     * Off by default: it is a diagnostic, not a permanent overlay. Its purpose is to make the
     * random tap point *visible* — a single coordinate in the panel's status line cannot show
     * whether taps are scattered through the click area or all land at its centre. Deliberately
     * independent of [showDetections]: seeing the tap scatter without the match boxes is the
     * normal way to use it, since the boxes are what make a run unreadable.
     */
    val showTaps: Boolean = false,
    /**
     * Show the runner's per-scan diagnostics in the floating panel's status line.
     *
     * The log is what explains a detection failure (frame size, the region each rule searched,
     * the best score) and it is several lines of exactly that once a rule works, which is why
     * it is a switch like the two overlay layers rather than a fixture. The panel's state line
     * ("Running: X" / "Idle") is not part of it and is never hidden — that is the panel's
     * indicator of what is running, not log output. On by default: it is the panel's
     * explanation of a run that finds nothing, and a switch that starts off hides exactly the
     * information needed to decide to turn it on.
     */
    val panelLog: Boolean = true,
    /**
     * Minimum time between screen scans while running.
     *
     * A floor, not a target: the loop waits only for the remainder of this after the work it
     * already did, so a scan costs capture + match + whatever is left of this. It was 500ms
     * while matching took seconds (where it was irrelevant), which silently capped a modern
     * scan to two per second — and an image that fades in and out within a second would be
     * sampled once or twice, or not at all.
     */
    val scanIntervalMs: Long = 150,
    /** Capture backend: see [com.screenclicker.capture.Capturers]. */
    val captureBackend: String = "auto",
    /** Show the floating control panel, so scripts run without opening the app. */
    val controlPanelEnabled: Boolean = false,
)

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
    /** Minimum time between screen scans while running. */
    val scanIntervalMs: Long = 500,
    /** Capture backend: see [com.screenclicker.capture.Capturers]. */
    val captureBackend: String = "auto",
)

package com.screenclicker.store

import android.content.Context
import android.content.SharedPreferences
import com.screenclicker.model.GlobalSettings

/**
 * SharedPreferences-backed global settings — synchronous reads matter for the scan
 * loop, which is why this is not JSON-in-files like scripts.
 */
class SettingsRepo(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(): GlobalSettings = GlobalSettings(
        defaultDelayMs = prefs.getLong(KEY_DELAY, 250L),
        defaultJitterMs = prefs.getLong(KEY_JITTER, 30L),
        defaultThreshold = prefs.getFloat(KEY_THRESHOLD, 0.90f),
        defaultIntervalMs = prefs.getLong(KEY_INTERVAL, 1_000L),
        showDetections = prefs.getBoolean(KEY_SHOW_DETECTIONS, true),
        scanIntervalMs = prefs.getLong(KEY_SCAN_INTERVAL, 500L),
        captureBackend = prefs.getString(KEY_BACKEND, "auto").orEmpty().ifBlank { "auto" },
    )

    fun save(settings: GlobalSettings) {
        prefs.edit()
            .putLong(KEY_DELAY, settings.defaultDelayMs)
            .putLong(KEY_JITTER, settings.defaultJitterMs)
            .putFloat(KEY_THRESHOLD, settings.defaultThreshold)
            .putLong(KEY_INTERVAL, settings.defaultIntervalMs)
            .putBoolean(KEY_SHOW_DETECTIONS, settings.showDetections)
            .putLong(KEY_SCAN_INTERVAL, settings.scanIntervalMs)
            .putString(KEY_BACKEND, settings.captureBackend)
            .apply()
    }

    /** Id of the most recently started script — the QS tile's toggle target. */
    fun lastRunScriptId(): String? = prefs.getString(KEY_LAST_RUN, null)

    fun setLastRunScriptId(id: String) {
        prefs.edit().putString(KEY_LAST_RUN, id).apply()
    }

    private companion object {
        const val KEY_DELAY = "defaultDelayMs"
        const val KEY_LAST_RUN = "lastRunScriptId"
        const val KEY_JITTER = "defaultJitterMs"
        const val KEY_THRESHOLD = "defaultThreshold"
        const val KEY_INTERVAL = "defaultIntervalMs"
        const val KEY_SHOW_DETECTIONS = "showDetections"
        const val KEY_SCAN_INTERVAL = "scanIntervalMs"
        const val KEY_BACKEND = "captureBackend"
    }
}

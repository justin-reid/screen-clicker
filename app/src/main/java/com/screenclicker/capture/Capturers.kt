package com.screenclicker.capture

import com.screenclicker.model.GlobalSettings

/**
 * Picks the capture backend per the user's setting:
 *  - media_projection: always try the fast backend (fails if not granted)
 *  - accessibility: never use MediaProjection
 *  - auto (default): fast backend when its projection is live, else accessibility
 */
object Capturers {

    const val BACKEND_AUTO = "auto"
    const val BACKEND_MEDIA_PROJECTION = "media_projection"
    const val BACKEND_ACCESSIBILITY = "accessibility"

    fun pick(settings: GlobalSettings): ScreenCapturer = when (settings.captureBackend) {
        BACKEND_MEDIA_PROJECTION -> MediaProjectionCapture()
        BACKEND_ACCESSIBILITY -> AccessibilityCapture()
        else ->
            if (CaptureProjectionService.isReady) MediaProjectionCapture()
            else AccessibilityCapture()
    }

    /**
     * The backend a rule's template was captured with, when it is usable right now. Used to
     * prefer the space the rule was authored in — see [com.screenclicker.model.Rule.frameWidth].
     */
    fun byName(name: String): ScreenCapturer? = when (name) {
        BACKEND_MEDIA_PROJECTION -> MediaProjectionCapture().takeIf { it.isAvailable() }
        BACKEND_ACCESSIBILITY -> AccessibilityCapture().takeIf { it.isAvailable() }
        else -> null
    }

    val knownBackends: List<String> = listOf(BACKEND_AUTO, BACKEND_MEDIA_PROJECTION, BACKEND_ACCESSIBILITY)
}

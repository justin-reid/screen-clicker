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
}

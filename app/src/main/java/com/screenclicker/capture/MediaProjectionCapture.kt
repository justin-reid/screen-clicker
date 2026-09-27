package com.screenclicker.capture

import com.screenclicker.accessibility.ClickerAccessibilityService

/**
 * [ScreenCapturer] over the MediaProjection session held by
 * [CaptureProjectionService]. Roughly frame-rate capture with no system throttle —
 * the fast path the accessibility backend cannot reach — at the cost of one consent
 * prompt per capture session.
 */
class MediaProjectionCapture : ScreenCapturer {

    override val name: String = "media_projection"

    override fun isAvailable(): Boolean = CaptureProjectionService.isReady

    override suspend fun capture(): CaptureResult = CaptureProjectionService.capture()
}

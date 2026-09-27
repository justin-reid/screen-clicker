package com.screenclicker.capture

import com.screenclicker.accessibility.ClickerAccessibilityService

/**
 * Capture backend backed by the accessibility service. Simple and always permitted once
 * the service is on, but system-throttled to roughly one frame per second — the fast
 * MediaProjection backend exists for when that is not enough.
 */
class AccessibilityCapture : ScreenCapturer {

    override val name: String = "accessibility"

    override fun isAvailable(): Boolean = ClickerAccessibilityService.isRunning

    override suspend fun capture(): CaptureResult = ClickerAccessibilityService.captureScreen()
}

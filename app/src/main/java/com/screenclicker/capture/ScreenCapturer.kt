package com.screenclicker.capture

/**
 * Where pixels come from. The runner is written against this interface only, so the
 * accessibility backend (simple, system-throttled) and the MediaProjection backend
 * (fast, needs consent) can be swapped without touching engine code.
 */
interface ScreenCapturer {

    val name: String

    /** False when the backend cannot currently produce pixels at all. */
    fun isAvailable(): Boolean

    /**
     * Captures one frame. Implementations throttle internally as their backend
     * requires; callers simply get a result (never an exception).
     */
    suspend fun capture(): CaptureResult
}

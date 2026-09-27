package com.screenclicker.capture

/**
 * The result of one screen capture, expressed as ARGB ints in row-major order.
 *
 * Pixels are plain ints (not Bitmap) from this layer down: everything the matcher
 * touches must stay JVM-testable, so Android types stop at this boundary.
 */
sealed class CaptureResult {

    data class Success(
        val argb: IntArray,
        val width: Int,
        val height: Int,
    ) : CaptureResult()

    data class Failure(
        val reason: String,
    ) : CaptureResult()
}

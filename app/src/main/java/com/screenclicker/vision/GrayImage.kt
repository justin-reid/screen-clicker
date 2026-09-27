package com.screenclicker.vision

/**
 * 8-bit grayscale image as a flat row-major array. Deliberately not [android.graphics.Bitmap]:
 * this package is pure Kotlin so its tests run on the JVM in CI with no emulator, which
 * is the whole reason the matcher can be trusted from a green build.
 */
class GrayImage(
    val width: Int,
    val height: Int,
    val pixels: IntArray,
) {
    init {
        require(width > 0 && height > 0) { "empty image: ${width}x$height" }
        require(pixels.size == width * height) {
            "size mismatch: ${pixels.size} for ${width}x$height"
        }
    }

    operator fun get(x: Int, y: Int): Int = pixels[y * width + x]

    companion object {
        /** ITU-R BT.601 luma in fixed point; the standard choice for ARGB ints. */
        fun fromArgb(argb: IntArray, width: Int, height: Int): GrayImage {
            require(argb.size >= width * height) { "argb too small: ${argb.size}" }
            val gray = IntArray(width * height)
            for (i in 0 until width * height) {
                val c = argb[i]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                gray[i] = (77 * r + 150 * g + 29 * b) shr 8
            }
            return GrayImage(width, height, gray)
        }
    }
}

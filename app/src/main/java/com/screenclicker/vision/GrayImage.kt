package com.screenclicker.vision

import com.screenclicker.model.PxRect

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
                gray[i] = luma(argb[i])
            }
            return GrayImage(width, height, gray)
        }

        /**
         * Grayscale of a single rectangle of a full-screen buffer.
         *
         * The scan loop only ever looks inside the union of the enabled rules' search
         * regions, so converting the whole screen is pure waste: a tall phone is ~2.8M
         * pixels, and that array plus its grayscale twin is ~23MB of garbage *per
         * frame* — the main reason the app felt laggy.
         */
        fun fromArgbRegion(argb: IntArray, width: Int, height: Int, region: PxRect): GrayImage {
            require(region.width > 0 && region.height > 0) { "empty region: $region" }
            require(region.left >= 0 && region.top >= 0) { "region off screen: $region" }
            require(region.right <= width && region.bottom <= height) {
                "region $region outside ${width}x$height"
            }
            val regionWidth = region.width
            val gray = IntArray(regionWidth * region.height)
            for (y in 0 until region.height) {
                val sourceRow = (region.top + y) * width + region.left
                val targetRow = y * regionWidth
                for (x in 0 until regionWidth) {
                    gray[targetRow + x] = luma(argb[sourceRow + x])
                }
            }
            return GrayImage(regionWidth, region.height, gray)
        }

        /** ITU-R BT.601 luma in fixed point; the standard choice for ARGB ints. */
        private fun luma(color: Int): Int {
            val r = (color shr 16) and 0xFF
            val g = (color shr 8) and 0xFF
            val b = color and 0xFF
            return (77 * r + 150 * g + 29 * b) shr 8
        }
    }
}

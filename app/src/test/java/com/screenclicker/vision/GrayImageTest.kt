package com.screenclicker.vision

import com.screenclicker.model.PxRect
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Test

class GrayImageTest {

    @Test
    fun `constructor rejects empty dimensions and mismatched storage`() {
        assertThrows(IllegalArgumentException::class.java) { GrayImage(0, 3, intArrayOf()) }
        assertThrows(IllegalArgumentException::class.java) { GrayImage(2, 2, intArrayOf(1, 2, 3)) }
    }

    @Test
    fun `ARGB conversion computes fixed point BT 601 luma into a new array`() {
        val argb = intArrayOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt())
        val gray = GrayImage.fromArgb(argb, 5, 1)
        assertArrayEquals(intArrayOf(0, 255, 76, 149, 28), gray.pixels)
        assertNotSame(argb, gray.pixels)
        assertEquals(255, gray[1, 0])
    }

    @Test
    fun `ARGB conversion tolerates extra source pixels and leaves them unused`() {
        val gray = GrayImage.fromArgb(intArrayOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt()), 1, 1)
        assertArrayEquals(intArrayOf(255), gray.pixels)
    }

    @Test
    fun `ARGB conversion rejects a too-small source buffer`() {
        assertThrows(IllegalArgumentException::class.java) {
            GrayImage.fromArgb(intArrayOf(0), 2, 1)
        }
    }

    @Test
    fun `region conversion returns row-major crop from full-frame coordinates`() {
        val argb = IntArray(4 * 3) { i ->
            val value = i * 10
            (0xFF shl 24) or (value shl 16) or (value shl 8) or value
        }
        val crop = GrayImage.fromArgbRegion(argb, 4, 3, PxRect(1, 1, 4, 3))
        assertEquals(3, crop.width)
        assertEquals(2, crop.height)
        assertArrayEquals(intArrayOf(50, 60, 70, 90, 100, 110), crop.pixels)
    }

    @Test
    fun `region conversion rejects empty and off-frame regions`() {
        val frame = IntArray(12)
        assertThrows(IllegalArgumentException::class.java) {
            GrayImage.fromArgbRegion(frame, 4, 3, PxRect(0, 0, 0, 3))
        }
        assertThrows(IllegalArgumentException::class.java) {
            GrayImage.fromArgbRegion(frame, 4, 3, PxRect(-1, 0, 2, 2))
        }
        assertThrows(IllegalArgumentException::class.java) {
            GrayImage.fromArgbRegion(frame, 4, 3, PxRect(2, 2, 5, 4))
        }
    }
}

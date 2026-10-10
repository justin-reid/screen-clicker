package com.screenclicker.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PxRectTest {

    @Test
    fun `dimensions centers and half-open containment are consistent`() {
        val rect = PxRect(10, 20, 30, 50)
        assertEquals(20, rect.width)
        assertEquals(30, rect.height)
        assertEquals(20, rect.centerX)
        assertEquals(35, rect.centerY)
        assertTrue(rect.contains(10, 20))
        assertTrue(rect.contains(29, 49))
        assertFalse(rect.contains(30, 49))
        assertFalse(rect.contains(29, 50))
    }

    @Test
    fun `rectangle containment includes matching edges`() {
        val outer = PxRect(10, 10, 40, 40)
        assertTrue(outer.contains(PxRect(10, 10, 40, 40)))
        assertTrue(outer.contains(PxRect(15, 15, 30, 30)))
        assertFalse(outer.contains(PxRect(9, 15, 30, 30)))
        assertFalse(outer.contains(PxRect(15, 15, 41, 30)))
    }

    @Test
    fun `translation shifts both corners without changing size`() {
        val shifted = PxRect(10, 20, 30, 50).translated(-4, 7)
        assertEquals(PxRect(6, 27, 26, 57), shifted)
        assertEquals(20, shifted.width)
        assertEquals(30, shifted.height)
    }

    @Test
    fun `clamping clips to screen dimensions and keeps empty results valid`() {
        assertEquals(PxRect(0, 0, 100, 80), PxRect(-5, -3, 140, 95).clampedTo(100, 80))
        assertEquals(PxRect(100, 80, 100, 80), PxRect(140, 95, 180, 130).clampedTo(100, 80))
        assertEquals(PxRect(0, 0, 0, 0), PxRect(-20, -10, -2, -1).clampedTo(100, 80))
    }

    @Test
    fun `union contains both input rectangles`() {
        val a = PxRect(10, 30, 40, 60)
        val b = PxRect(25, 5, 70, 42)
        val union = a.union(b)
        assertEquals(PxRect(10, 5, 70, 60), union)
        assertTrue(union.contains(a))
        assertTrue(union.contains(b))
    }

    @Test
    fun `inset shrinks by fraction of the shorter side`() {
        assertEquals(PxRect(4, 4, 96, 36), PxRect(0, 0, 100, 40).insetFraction(0.1f))
        assertEquals(PxRect(5, 5, 5, 5), PxRect(5, 5, 5, 5).insetFraction(0.2f))
    }

    @Test
    fun `random tap points stay inside the rectangle and away from edges`() {
        val rect = PxRect(10, 20, 110, 80)
        repeat(500) {
            val (x, y) = rect.randomPoint()
            assertTrue(x in 16 until 104)
            assertTrue(y in 26 until 74)
        }
    }

    @Test
    fun `degenerate thin bounds still produce valid center-line points`() {
        val vertical = PxRect(8, 10, 8, 30)
        repeat(100) {
            val (x, y) = vertical.randomPoint()
            assertEquals(8, x)
            assertTrue(y in 10 until 30)
        }
        assertEquals(8 to 10, PxRect(8, 10, 8, 11).randomPoint())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `inverted rectangles are rejected`() {
        PxRect(10, 0, 5, 20)
    }
}

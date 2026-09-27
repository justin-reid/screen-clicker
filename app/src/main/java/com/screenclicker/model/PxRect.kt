package com.screenclicker.model

import kotlinx.serialization.Serializable
import kotlin.random.Random

/**
 * A rectangle in **physical screen pixels**. Everything in this app — search regions,
 * click regions, detected matches — lives in this coordinate space, matching both what
 * screenshots arrive in and what dispatchGesture expects.
 */
@Serializable
data class PxRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2

    init {
        require(left <= right && top <= bottom) { "inverted rect: $this" }
    }

    fun contains(x: Int, y: Int): Boolean = x in left until right && y in top until bottom

    fun contains(other: PxRect): Boolean =
        other.left >= left && other.right <= right && other.top >= top && other.bottom <= bottom

    /** Intersects with screen bounds; empty results stay empty rather than invalid. */
    fun clampedTo(screenWidth: Int, screenHeight: Int): PxRect {
        val l = left.coerceIn(0, screenWidth)
        val t = top.coerceIn(0, screenHeight)
        val r = right.coerceIn(0, screenWidth)
        val b = bottom.coerceIn(0, screenHeight)
        return PxRect(l, t, maxOf(l, r), maxOf(t, b))
    }

    /** Smallest rect containing both — used to convert one screen region once. */
    fun union(other: PxRect): PxRect = PxRect(
        minOf(left, other.left),
        minOf(top, other.top),
        maxOf(right, other.right),
        maxOf(bottom, other.bottom),
    )

    /**
     * Shrunk by [fraction] of the smaller dimension on every side — the safety margin
     * used before picking a random tap point, so a tap can never land on the rect edge.
     */
    fun insetFraction(fraction: Float): PxRect {
        val margin = (minOf(width, height) * fraction).toInt()
        val l = (left + margin).coerceAtMost(right)
        val t = (top + margin).coerceAtMost(bottom)
        return PxRect(l, t, maxOf(l, right - margin), maxOf(t, bottom - margin))
    }

    /**
     * Uniform random point inside the rect. Inset first so taps never land on borders
     * and almost never repeat; degenerate (line-thin) rects degrade to their center line.
     */
    fun randomPoint(random: Random = Random.Default): Pair<Int, Int> {
        val inset = insetFraction(TAP_INSET_FRACTION)
        val x = if (inset.width > 1) (inset.left until inset.right).random(random) else centerX
        val y = if (inset.height > 1) (inset.top until inset.bottom).random(random) else centerY
        return x to y
    }

    companion object {
        const val TAP_INSET_FRACTION = 0.1f
    }
}

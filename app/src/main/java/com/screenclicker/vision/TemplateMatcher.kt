package com.screenclicker.vision

import com.screenclicker.model.PxRect
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class MatchResult(
    val score: Float,
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
) {
    val centerX: Int get() = left + width / 2
    val centerY: Int get() = top + height / 2
}

/**
 * Template matching by normalized cross-correlation (ZNCC), coarse-to-fine.
 *
 * Why ZNCC and not plain pixel difference: screens change brightness (dark mode,
 * overlays, dimming) and ZNCC is invariant to affine intensity shifts, so a slightly
 * brighter or darker render of the same UI still matches. Score is 0..1; 1.0 is a
 * pixel-perfect match. The user-facing similarity threshold is [findBest]'s minScore.
 *
 * Cost control: a naive full-resolution scan costs candidates x template-pixels, which
 * is billions of operations for a full-screen search. The coarse pass scans
 * half-resolution images (1/4 the candidates, 1/4 the work each), and the refine pass
 * re-scores a small full-resolution window around the coarse winner, so accuracy is
 * preserved at a fraction of the cost.
 *
 * Sub-pixel phase matters at half resolution: a 2x2 box filter is anchored to the even
 * grid, so a feature offset by one pixel blurs differently depending on parity. The
 * coarse pass therefore runs on all four phase grids (x,y offsets 0/1) and every
 * candidate maps exactly onto its phase — verified by tests on odd-offset exact matches.
 *
 * Pure Kotlin (IntArray, no Android types) — JVM-testable.
 */
object TemplateMatcher {

    /** Below this size a half-resolution pass would crush detail; scan full-res directly. */
    private const val COARSE_MIN_TEMPLATE = 8

    /** Full-res refine window radius (px) around the coarse winner. */
    private const val REFINE_RADIUS = 3

    /** A template flatter than this (std < 0.5) has no usable ZNCC gradient; use MAD. */
    private const val UNIFORM_VAR_PER_PX = 0.25

    fun findBest(
        screen: GrayImage,
        template: GrayImage,
        region: PxRect?,
        minScore: Float,
    ): MatchResult? {
        val left = max(0, region?.left ?: 0)
        val top = max(0, region?.top ?: 0)
        val right = min(screen.width, region?.right ?: screen.width)
        val bottom = min(screen.height, region?.bottom ?: screen.height)
        if (left >= right || top >= bottom) return null

        val bounds = PxRect(left, top, right, bottom)
        if (template.width > bounds.width || template.height > bounds.height) return null

        // Candidate top-left ranges, relative to bounds origin, full resolution.
        val relXMax = bounds.width - template.width
        val relYMax = bounds.height - template.height

        // When the coarse pass runs, its verdict is trusted: refine only around its
        // winner. A true >= minScore match at full resolution scores nearly the same
        // coarse, so skipping the broad full-res scan on no-match frames (the common
        // case for a waiting clicker) loses nothing measurable and saves the single
        // most expensive step.
        val coarseWinner = runCoarsePass(screen, template, bounds, relXMax, relYMax)
        if (coarseWinner != null) {
            val approxRelX = (coarseWinner.left - bounds.left).coerceIn(0, relXMax)
            val approxRelY = (coarseWinner.top - bounds.top).coerceIn(0, relYMax)
            val integrals = Integrals(screen, bounds)
            val rxMin = max(0, approxRelX - REFINE_RADIUS)
            val ryMin = max(0, approxRelY - REFINE_RADIUS)
            val rxMax = min(relXMax, approxRelX + REFINE_RADIUS)
            val ryMax = min(relYMax, approxRelY + REFINE_RADIUS)
            val refined = scan(
                screen,
                template,
                bounds,
                integrals,
                rxMin,
                ryMin,
                rxMax,
                ryMax,
                stride = 1,
            )
            return refined?.takeIf { it.score >= minScore }
        }

        // Small template (or degenerate region): exhaustive full-resolution scan. This
        // path is reached only for tiny templates or 1-2 candidate positions, so the
        // cost is trivial — and only a stride-1 scan is correct here: a strided scan
        // would skip half the parities, and on low-correlation content (noise, dithered
        // backgrounds) the skipped true match could lose to a lucky candidate.
        val integrals = Integrals(screen, bounds)
        val result = scan(screen, template, bounds, integrals, 0, 0, relXMax, relYMax, stride = 1)
        return result?.takeIf { it.score >= minScore }
    }

    /**
     * Half-resolution scan over the whole region across all four phase grids; returns
     * the winner in full-resolution screen coordinates, or null when the region or
     * template is too small for a meaningful coarse pass.
     */
    private fun runCoarsePass(
        screen: GrayImage,
        template: GrayImage,
        bounds: PxRect,
        relXMax: Int,
        relYMax: Int,
    ): MatchResult? {
        if (template.width < COARSE_MIN_TEMPLATE || template.height < COARSE_MIN_TEMPLATE) return null
        if (relXMax < 2 || relYMax < 2) return null

        val coarseTemplate = downsample2(template)
        var best: MatchResult? = null

        for (phaseX in 0..1) {
            for (phaseY in 0..1) {
                val phaseScreen = downsample2(screen, phaseX, phaseY)
                if (coarseTemplate.width > phaseScreen.width ||
                    coarseTemplate.height > phaseScreen.height
                ) {
                    continue
                }
                // Full-res top-left X = 2*cx + phaseX must satisfy
                // bounds.left <= X <= bounds.left + relXMax, and the window must fit
                // inside the phase image.
                val cxMin = max(0, (bounds.left - phaseX + 1) / 2)
                val cxMax = min(
                    phaseScreen.width - coarseTemplate.width,
                    (bounds.left + relXMax - phaseX) / 2,
                )
                val cyMin = max(0, (bounds.top - phaseY + 1) / 2)
                val cyMax = min(
                    phaseScreen.height - coarseTemplate.height,
                    (bounds.top + relYMax - phaseY) / 2,
                )
                if (cxMin > cxMax || cyMin > cyMax) continue

                val phaseBounds = PxRect(0, 0, phaseScreen.width, phaseScreen.height)
                val hit = scan(
                    phaseScreen,
                    coarseTemplate,
                    phaseBounds,
                    Integrals(phaseScreen, phaseBounds),
                    cxMin,
                    cyMin,
                    cxMax,
                    cyMax,
                    stride = 1,
                ) ?: continue

                // Map the phase-space winner back to full-resolution coordinates.
                val fullX = hit.left * 2 + phaseX
                val fullY = hit.top * 2 + phaseY
                if (best == null || hit.score > best.score) {
                    best = MatchResult(hit.score, fullX, fullY, template.width, template.height)
                }
            }
        }
        return best
    }

    /**
     * 2x2 box-filter downsample anchored at (phaseX, phaseY); output size drops a
     * trailing odd row/column per phase. All block pixels are in bounds by construction.
     */
    internal fun downsample2(img: GrayImage, phaseX: Int = 0, phaseY: Int = 0): GrayImage {
        val w2 = (img.width - phaseX) / 2
        val h2 = (img.height - phaseY) / 2
        val out = IntArray(w2 * h2)
        for (y in 0 until h2) {
            val rowA = (2 * y + phaseY) * img.width
            val rowB = rowA + img.width
            for (x in 0 until w2) {
                val ax = 2 * x + phaseX
                out[y * w2 + x] = (img.pixels[rowA + ax] + img.pixels[rowA + ax + 1] +
                    img.pixels[rowB + ax] + img.pixels[rowB + ax + 1]) shr 2
            }
        }
        return GrayImage(w2, h2, out)
    }

    /**
     * ZNCC scan over candidate top-left positions. (rxMin, ryMin)..(rxMax, ryMax) are
     * inclusive, relative to [bounds]'s origin; returned coordinates are in the image
     * space of [screen].
     */
    private fun scan(
        screen: GrayImage,
        template: GrayImage,
        bounds: PxRect,
        integrals: Integrals,
        relXMin: Int,
        relYMin: Int,
        relXMax: Int,
        relYMax: Int,
        stride: Int,
    ): MatchResult? {
        if (relXMin > relXMax || relYMin > relYMax) return null

        val sw = screen.width
        val sp = screen.pixels
        val tw = template.width
        val th = template.height
        val tp = template.pixels
        val n = tw * th

        var tSum = 0.0
        var tSumSq = 0.0
        for (v in tp) {
            tSum += v
            tSumSq += (v * v).toDouble()
        }
        val tMean = tSum / n
        val tVarN = tSumSq - tSum * tMean // = sum((t - mean)^2)
        val uniform = tVarN < UNIFORM_VAR_PER_PX * n
        val uniformValue = tMean.toInt().coerceIn(0, 255)

        var best: MatchResult? = null
        var bestScore = Float.NEGATIVE_INFINITY

        var ry = relYMin
        while (ry <= relYMax) {
            var rx = relXMin
            while (rx <= relXMax) {
                val wSum = integrals.areaSum(rx, ry, tw, th)
                if (uniform) {
                    // MAD scoring: score = 1 - meanAbsDiff/255.
                    var sad = 0L
                    val base = (bounds.top + ry) * sw + bounds.left + rx
                    for (ty in 0 until th) {
                        val row = base + ty * sw
                        for (tx in 0 until tw) {
                            sad += Math.abs(sp[row + tx] - uniformValue)
                        }
                    }
                    val score = 1f - (sad.toFloat() / (n * 255f))
                    if (score > bestScore) {
                        bestScore = score
                        best = MatchResult(score, bounds.left + rx, bounds.top + ry, tw, th)
                    }
                } else {
                    val wSumSq = integrals.areaSumSq(rx, ry, tw, th)
                    val wVarN = wSumSq - wSum.toDouble() * wSum / n
                    // Flat screen windows have no structure to correlate; skip the
                    // expensive loop. (A flat window would score ~0 anyway.)
                    if (wVarN > 1.0) {
                        var cross = 0L
                        val base = (bounds.top + ry) * sw + bounds.left + rx
                        for (ty in 0 until th) {
                            val row = base + ty * sw
                            val tRow = ty * tw
                            for (tx in 0 until tw) {
                                cross += (tp[tRow + tx] * sp[row + tx]).toLong()
                            }
                        }
                        val num = cross - tMean * wSum
                        val den = sqrt(wVarN * tVarN)
                        val score = if (den > 0.0) (num / den).toFloat().coerceIn(0f, 1f) else 0f
                        if (score > bestScore) {
                            bestScore = score
                            best = MatchResult(score, bounds.left + rx, bounds.top + ry, tw, th)
                        }
                    }
                }
                rx += stride
            }
            ry += stride
        }
        return best
    }

    /**
     * Summed-area tables over [bounds] only (not the whole screen — a full-screen
     * table at 1440p would cost ~50 MB transient). Indices are relative to bounds.
     */
    private class Integrals(img: GrayImage, bounds: PxRect) {
        private val rw = bounds.width
        private val rh = bounds.height
        private val stride = rw + 1
        private val sum = IntArray(stride * (rh + 1))
        private val sumSq = LongArray(stride * (rh + 1))

        init {
            for (y in 0 until rh) {
                var rowAcc = 0
                var rowAccSq = 0L
                for (x in 0 until rw) {
                    val v = img[bounds.left + x, bounds.top + y]
                    rowAcc += v
                    rowAccSq += (v * v).toLong()
                    val idx = (y + 1) * stride + (x + 1)
                    sum[idx] = sum[y * stride + (x + 1)] + rowAcc
                    sumSq[idx] = sumSq[y * stride + (x + 1)] + rowAccSq
                }
            }
        }

        fun areaSum(rx: Int, ry: Int, w: Int, h: Int): Int {
            val x = rx + w
            val y = ry + h
            return sum[y * stride + x] - sum[ry * stride + x] -
                sum[y * stride + rx] + sum[ry * stride + rx]
        }

        fun areaSumSq(rx: Int, ry: Int, w: Int, h: Int): Long {
            val x = rx + w
            val y = ry + h
            return sumSq[y * stride + x] - sumSq[ry * stride + x] -
                sumSq[y * stride + rx] + sumSq[ry * stride + rx]
        }
    }
}

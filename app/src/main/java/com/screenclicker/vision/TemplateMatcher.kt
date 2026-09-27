package com.screenclicker.vision

import com.screenclicker.model.PxRect
import kotlin.math.abs
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

/** How far a result can be trusted by a caller that has to draw or tap something. */
enum class Confidence {
    /** One window clearly wins: act on it. */
    SHARP,

    /** Several windows score alike, so the winner is not reliably *the* image. */
    AMBIGUOUS,

    /** The template itself has no structure, so nothing can be located with it. */
    NO_CONTRAST,

    /** Nothing was found. */
    NONE,
}

/**
 * One search: the best window, the best *unrelated* window, and how much the first can be
 * trusted.
 *
 * The runner reports all three, because a bare score cannot tell "the image is here" from
 * "two bland patches happened to tie at 75%", and that difference is the whole reason a
 * highlight can land next to the image instead of on it.
 */
data class SearchOutcome(
    val best: MatchResult?,
    /** Best score at least one minimum separation away from [best]; [NO_RUNNER_UP] if none. */
    val runnerUp: Float,
    val confidence: Confidence,
) {
    companion object {
        const val NO_RUNNER_UP = -1f
    }
}

/**
 * Template matching by normalized cross-correlation (ZNCC), coarse-to-fine.
 *
 * Why ZNCC and not plain pixel difference: screens change brightness (dark mode, overlays,
 * dimming) and ZNCC is invariant to affine intensity shifts, so a slightly brighter or
 * darker render of the same UI still matches — and so does a *fading* one, which is the
 * case that matters for an image that fades in over a second: a blend of the template with
 * a flat background keeps a fixed fraction of the template's contrast, and ZNCC peaks at
 * the same place for any fraction of it.
 *
 * Cost control: a naive full-resolution scan costs candidates x template-pixels, which is
 * billions of operations for a screen-sized region and measured 2.9s of matching per scan
 * on the target phone — three times longer than the whole on-screen event it was trying to
 * catch. The search is therefore a pyramid:
 *
 *  - the coarse pass correlates box-filtered images, which costs 1/16 of the candidates and
 *    1/16 of the work per candidate at quarter resolution, 1/64 at eighth resolution;
 *  - the reduction factor is chosen as large as the template can stand (its coarse copy must
 *    still be at least [MIN_COARSE_SIDE] pixels across), because the whole cost of the coarse
 *    pass falls with the fourth power of that factor;
 *  - it keeps the few best *separated* coarse winners rather than only the top one, because a
 *    blurred decoy can outrank the real (possibly faded) target, and refining a handful of
 *    windows costs almost nothing;
 *  - the coarse winner is then re-scored at full resolution in a small window, so the position
 *    and the score that get reported are exact pixels.
 *
 * The coarse pass correlates a *single* box-filtered screen against all four phase variants of
 * the template (box starting at each of the 2x2 sub-offsets). Box filtering is anchored to the
 * grid, so a target at an odd offset is blurred differently from an even one, and comparing a
 * single template phase against a single screen phase would miss half of the alignments — for
 * high-frequency content (text, dithered backgrounds) badly enough to rank the true position
 * nowhere near the top. Varying the *template* phase instead of the screen phase covers the
 * same four comparisons while downsampling the screen once. The residual alignment error after
 * the best phase wins is at most half a coarse pixel, which is what [refineRadius] covers by
 * re-scoring every full-resolution position in that window.
 *
 * Two guards keep a *plausible* score from being acted on as a location:
 *  - a window must carry its own contrast, relative to the template's (see
 *    [CONTRAST_FRACTION]) — a flat window has no structure, so any correlation it shows is
 *    curve-fitting noise;
 *  - a result below [CONFIDENT_SCORE] with a runner-up within [AMBIGUITY_MARGIN] is reported
 *    as ambiguous instead of as a find, because "which of these two spots" is not a question
 *    the caller can answer.
 *
 * Pure Kotlin (IntArray, no Android types) — JVM-testable.
 */
object TemplateMatcher {

    /**
     * Templates whose smaller side is below this are scanned at full resolution: the coarse
     * copy has to be at least [MIN_COARSE_SIDE] across, so a factor-2 reduction needs 16px.
     * Small templates are cheap to scan exhaustively anyway (few pixels per candidate).
     */
    private const val COARSE_MIN_TEMPLATE = 16 // = 2 * MIN_COARSE_SIDE

    /**
     * Smallest side of a template's box-filtered copy. Below this the coarse pass is
     * comparing a handful of blocks, which ranks windows by too little to be trusted — and
     * the exhaustive scan it avoids is cheap for a template that small.
     */
    private const val MIN_COARSE_SIDE = 8

    /**
     * Below this many multiply-accumulates, an exhaustive full-resolution scan is cheap
     * enough that the pyramid only adds its own overhead.
     */
    private const val EXHAUSTIVE_BUDGET = 4_000_000L

    /**
     * Full-resolution refine radius around a coarse winner: half a coarse pixel of residual
     * alignment error, plus a margin. Off-grid positions inside a coarse block must still
     * refine to the exact pixel, which the tests pin.
     */
    private fun refineRadius(factor: Int): Int = factor / 2 + 2

    /**
     * Ceiling on the coarse factor.
     *
     * The coarse cost falls with the fourth power of the factor while the refine cost grows
     * with its square (the refine window has to cover half a coarse pixel), so past this point
     * a finer coarse grid is no longer worth its blur: for a screen-sized region the total
     * bottoms out around 8, and an 8x8 box average is already very soft.
     */
    private const val MAX_COARSE_FACTOR = 8

    /** Coarse winners refined at full resolution. The extras feed the ambiguity check. */
    private const val COARSE_CANDIDATES = 4

    /** A template whose contrast is below this (gray levels) cannot say *where* anything is. */
    private const val MIN_TEMPLATE_STD = 2.0

    /**
     * A window must carry at least this fraction of the template's variance.
     *
     * A fade multiplies contrast by alpha, so variance by alpha^2: 2% of the template's
     * variance is reached at alpha ~= 0.14, i.e. the image is matched from the moment it is a
     * seventh as visible as the template it was cropped from — which is the point of matching
     * something that fades in. A flat background patch has no structure of its own and is
     * excluded by [MIN_WINDOW_VAR] instead.
     */
    private const val CONTRAST_FRACTION = 0.02

    /**
     * ...and at least this variance per pixel (one gray level of standard deviation), however
     * faint the template is, so that two flat-ish windows are never separated by noise. This is
     * what excludes a blank background window: its variance is quantisation noise, well under
     * one gray level.
     */
    private const val MIN_WINDOW_VAR = 1.0

    /** At or above this score the winner is sharp enough that a rival does not matter. */
    private const val CONFIDENT_SCORE = 0.95f

    /** Below [CONFIDENT_SCORE], a runner-up this close means "cannot tell which one". */
    private const val AMBIGUITY_MARGIN = 0.05f

    /**
     * Best match of [template] inside [region], or null when the search cannot say where the
     * image is at all: nothing scored [minScore], the find was ambiguous, or the template has
     * no contrast to locate with.
     */
    fun findBest(
        screen: GrayImage,
        template: GrayImage,
        region: PxRect?,
        minScore: Float,
    ): MatchResult? {
        val outcome = search(screen, template, region)
        if (outcome.confidence != Confidence.SHARP) return null
        return outcome.best?.takeIf { it.score >= minScore }
    }

    /**
     * True when [template] has enough structure to say *where* a match is.
     *
     * The editor says so when a captured crop does not: a template of nothing but background
     * cannot be located at all, and every search with it would offer a best-effort position
     * that means nothing — the shape of "it detected, but the box was nowhere near the image".
     */
    fun isLocatable(template: GrayImage): Boolean =
        sqrt(stats(template).varPerPx) >= MIN_TEMPLATE_STD

    /** The same search as [findBest], with the information the caller needs to explain it. */
    fun search(
        screen: GrayImage,
        template: GrayImage,
        region: PxRect?,
    ): SearchOutcome {
        val left = max(0, region?.left ?: 0)
        val top = max(0, region?.top ?: 0)
        val right = min(screen.width, region?.right ?: screen.width)
        val bottom = min(screen.height, region?.bottom ?: screen.height)
        if (left >= right || top >= bottom) return notFound()
        if (template.width > right - left || template.height > bottom - top) return notFound()

        val bounds = PxRect(left, top, right, bottom)
        val n = template.width * template.height
        val tStats = stats(template)
        if (sqrt(tStats.varPerPx) < MIN_TEMPLATE_STD) {
            return SearchOutcome(null, SearchOutcome.NO_RUNNER_UP, Confidence.NO_CONTRAST)
        }
        val windowVarFloor = max(MIN_WINDOW_VAR, CONTRAST_FRACTION * tStats.varPerPx)

        // Candidate top-left ranges, relative to bounds origin, full resolution.
        val relXMax = bounds.width - template.width
        val relYMax = bounds.height - template.height

        // Windows closer than this are the same find, not two: half a template apart is
        // already a visibly different spot, and inside it they overlap the same object.
        val separation = max(8, min(template.width, template.height) / 2)
        val refined = Candidates(limit = 2, separation = separation)

        val factor = coarseFactor(template, relXMax, relYMax)
        if (factor == 0) {
            // Too small to survive a box filter: exhaustive full-resolution scan. Cheap,
            // because a tiny template has few pixels per candidate position.
            val integrals = Integrals(screen, bounds)
            scan(
                screen, template, bounds, integrals, refined,
                0, 0, relXMax, relYMax, stride = 1, windowVarFloor, tStats,
            )
        } else {
            val seeds = coarseSeeds(screen, template, bounds, relXMax, relYMax, factor, separation)
            val integrals = Integrals(screen, bounds)
            val radius = refineRadius(factor)
            for (seed in seeds) {
                val approxRelX = (seed.left - bounds.left).coerceIn(0, relXMax)
                val approxRelY = (seed.top - bounds.top).coerceIn(0, relYMax)
                scan(
                    screen, template, bounds, integrals, refined,
                    max(0, approxRelX - radius),
                    max(0, approxRelY - radius),
                    min(relXMax, approxRelX + radius),
                    min(relYMax, approxRelY + radius),
                    stride = 1, windowVarFloor, tStats,
                )
            }
        }

        val best = refined.best ?: return notFound()
        val runnerUp = refined.runnerUpScore
        val ambiguous = best.score < CONFIDENT_SCORE &&
            runnerUp >= best.score - AMBIGUITY_MARGIN
        return SearchOutcome(
            best = best,
            runnerUp = runnerUp,
            confidence = if (ambiguous) Confidence.AMBIGUOUS else Confidence.SHARP,
        )
    }

    private fun notFound() =
        SearchOutcome(null, SearchOutcome.NO_RUNNER_UP, Confidence.NONE)

    /**
     * Coarse pyramid factor for this template and candidate range, or 0 to scan at full
     * resolution.
     *
     * As large as the template allows, because the coarse pass costs region-area x
     * template-area / factor^4: one factor step is 16x off the total. The bound is what the
     * template can survive — its coarse copy must stay at least [MIN_COARSE_SIDE] across for
     * the ranking to mean anything.
     */
    private fun coarseFactor(template: GrayImage, relXMax: Int, relYMax: Int): Int {
        val smaller = min(template.width, template.height)
        if (smaller < COARSE_MIN_TEMPLATE) return 0
        // Few enough positions that scanning them all is cheaper than building a pyramid.
        val positions = (relXMax + 1).toLong() * (relYMax + 1).toLong()
        val perPosition = template.width.toLong() * template.height
        if (positions * perPosition <= EXHAUSTIVE_BUDGET) return 0
        var factor = 2
        while (factor * 2 * MIN_COARSE_SIDE <= smaller && factor < MAX_COARSE_FACTOR) factor *= 2
        return factor
    }

    /**
     * The few best separated windows at [factor]-reduced resolution, mapped back to
     * full-resolution screen coordinates.
     *
     * Every template phase variant is scanned against the one box-filtered screen (see the
     * class doc), and each phase reports the full-resolution position it is aligned to:
     * f*cx - phase, because a template box starting at its own pixel [phase] lines up with the
     * screen box starting at f*cx.
     *
     * No contrast floor here: box averaging divides a window's variance, so the full-resolution
     * floor (where the numbers mean something) is applied in [scan] instead. A coarse decoy is
     * harmless — it only costs one small refine pass, and the full-res score decides.
     */
    private fun coarseSeeds(
        screen: GrayImage,
        template: GrayImage,
        bounds: PxRect,
        relXMax: Int,
        relYMax: Int,
        factor: Int,
        separation: Int,
    ): List<MatchResult> {
        val coarseScreen = downsample(screen, factor, 0, 0)
        val coarseBounds = PxRect(0, 0, coarseScreen.width, coarseScreen.height)
        val coarseIntegrals = Integrals(coarseScreen, coarseBounds)
        val seeds = Candidates(limit = COARSE_CANDIDATES, separation = separation)

        for (phaseX in 0..1) {
            for (phaseY in 0..1) {
                val coarseTemplate = downsample(template, factor, phaseX, phaseY)
                if (coarseTemplate.width > coarseScreen.width ||
                    coarseTemplate.height > coarseScreen.height
                ) {
                    continue
                }
                // Full-res top-left X = factor * cx - phaseX must satisfy
                // bounds.left <= X <= bounds.left + relXMax, and the window must fit inside
                // the coarse image.
                val cxMin = max(0, ceilDiv(bounds.left + phaseX, factor))
                val cxMax = min(
                    coarseScreen.width - coarseTemplate.width,
                    floorDiv(bounds.left + relXMax + phaseX, factor),
                )
                val cyMin = max(0, ceilDiv(bounds.top + phaseY, factor))
                val cyMax = min(
                    coarseScreen.height - coarseTemplate.height,
                    floorDiv(bounds.top + relYMax + phaseY, factor),
                )
                if (cxMin > cxMax || cyMin > cyMax) continue

                // Separated in *coarse* pixels: these are coarse positions, and two of them
                // a couple of coarse pixels apart are the same find.
                val phaseSeeds = Candidates(
                    limit = COARSE_CANDIDATES,
                    separation = max(2, separation / factor),
                )
                scan(
                    coarseScreen, coarseTemplate, coarseBounds, coarseIntegrals, phaseSeeds,
                    cxMin, cyMin, cxMax, cyMax, stride = 1,
                    windowVarFloor = -1.0, // no floor at this scale (see above)
                    templateStats = stats(coarseTemplate),
                )
                for (seed in phaseSeeds.items) {
                    seeds.offer(
                        MatchResult(
                            seed.score,
                            seed.left * factor - phaseX,
                            seed.top * factor - phaseY,
                            template.width,
                            template.height,
                        ),
                    )
                }
            }
        }
        return seeds.items
    }

    /**
     * [factor] x [factor] box-filter downsample, anchored at (phaseX, phaseY); output drops
     * any trailing partial block per phase. The template's own phases are what cover the
     * alignment the anchor misses (see the class doc).
     */
    internal fun downsample(img: GrayImage, factor: Int, phaseX: Int = 0, phaseY: Int = 0): GrayImage {
        if (factor <= 1) return img
        val w = (img.width - phaseX) / factor
        val h = (img.height - phaseY) / factor
        val out = IntArray(w * h)
        val block = factor * factor
        for (y in 0 until h) {
            val top = y * factor + phaseY
            for (x in 0 until w) {
                val left = x * factor + phaseX
                var sum = 0
                for (dy in 0 until factor) {
                    val row = (top + dy) * img.width + left
                    for (dx in 0 until factor) sum += img.pixels[row + dx]
                }
                out[y * w + x] = sum / block
            }
        }
        return GrayImage(w, h, out)
    }

    /**
     * ZNCC over candidate top-left positions. (relXMin, relYMin)..(relXMax, relYMax) are
     * inclusive and relative to [bounds]'s origin; offered coordinates are in the image space
     * of [screen]. A window is offered only when it carries enough contrast of its own.
     */
    private fun scan(
        screen: GrayImage,
        template: GrayImage,
        bounds: PxRect,
        integrals: Integrals,
        candidates: Candidates,
        relXMin: Int,
        relYMin: Int,
        relXMax: Int,
        relYMax: Int,
        stride: Int,
        windowVarFloor: Double,
        templateStats: Stats,
    ) {
        if (relXMin > relXMax || relYMin > relYMax) return

        val sw = screen.width
        val sp = screen.pixels
        val tw = template.width
        val th = template.height
        val tp = template.pixels
        val n = tw * th
        val tMean = templateStats.mean
        val tVarN = templateStats.varN

        var ry = relYMin
        while (ry <= relYMax) {
            var rx = relXMin
            while (rx <= relXMax) {
                val wSum = integrals.areaSum(rx, ry, tw, th)
                val wSumSq = integrals.areaSumSq(rx, ry, tw, th)
                val wVarN = wSumSq - wSum.toDouble() * wSum / n
                // Flat windows have no structure to correlate: every score they produce is
                // noise fitted to the template, and acting on one is how a highlight ends up
                // somewhere the image is not. (Faded targets keep a fraction of the
                // template's variance, so this excludes blanks without excluding fades.)
                if (windowVarFloor < 0 || wVarN > windowVarFloor * n) {
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
                    if (den > 0.0) {
                        val score = (num / den).toFloat().coerceIn(0f, 1f)
                        candidates.offer(
                            MatchResult(score, bounds.left + rx, bounds.top + ry, tw, th),
                        )
                    }
                }
                rx += stride
            }
            ry += stride
        }
    }

    /** Mean and unscaled variance sums of one image. */
    private class Stats(val mean: Double, val varN: Double, val varPerPx: Double)

    private fun stats(image: GrayImage): Stats {
        val n = image.width * image.height
        var sum = 0.0
        var sumSq = 0.0
        for (v in image.pixels) {
            sum += v
            sumSq += (v * v).toDouble()
        }
        val mean = sum / n
        val varN = sumSq - sum * mean
        return Stats(mean, varN, varN / n)
    }

    /**
     * Up to [limit] best-scoring windows, none of them within [separation] pixels of another
     * kept one. Two windows that close are the same find, so only the better one is kept.
     */
    private class Candidates(private val limit: Int, private val separation: Int) {
        private val kept = ArrayList<MatchResult>(limit)

        val items: List<MatchResult> get() = kept

        val best: MatchResult? get() = kept.firstOrNull()

        val runnerUpScore: Float get() = kept.getOrNull(1)?.score ?: SearchOutcome.NO_RUNNER_UP

        fun offer(candidate: MatchResult) {
            for (existing in kept) {
                if (sameFind(existing, candidate)) {
                    // Same spot, so the better score is the one worth keeping.
                    if (candidate.score > existing.score) {
                        kept[kept.indexOf(existing)] = candidate
                        kept.sortByDescending { it.score }
                    }
                    return
                }
            }
            if (kept.size < limit) {
                kept.add(candidate)
                kept.sortByDescending { it.score }
                return
            }
            val worst = kept.last()
            if (candidate.score <= worst.score) return
            kept[kept.size - 1] = candidate
            kept.sortByDescending { it.score }
        }

        private fun sameFind(a: MatchResult, b: MatchResult): Boolean =
            abs(a.left - b.left) < separation && abs(a.top - b.top) < separation
    }

    /** Both are called with non-negative values only (screen and region coordinates). */
    private fun ceilDiv(value: Int, factor: Int): Int = (value + factor - 1) / factor

    private fun floorDiv(value: Int, factor: Int): Int = value / factor

    /**
     * Summed-area tables over [bounds] only (not the whole screen — a full-screen table at
     * 1440p would cost ~50 MB transient). Indices are relative to bounds.
     */
    private class Integrals(img: GrayImage, bounds: PxRect) {
        private val stride = bounds.width + 1
        private val sum = IntArray(stride * (bounds.height + 1))
        private val sumSq = LongArray(stride * (bounds.height + 1))

        init {
            for (y in 0 until bounds.height) {
                var rowAcc = 0
                var rowAccSq = 0L
                for (x in 0 until bounds.width) {
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

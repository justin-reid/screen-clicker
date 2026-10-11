package com.screenclicker.engine

import com.screenclicker.capture.CaptureResult
import com.screenclicker.capture.ScreenCapturer
import com.screenclicker.model.Cadence
import com.screenclicker.model.ClickMode
import com.screenclicker.model.GlobalSettings
import com.screenclicker.model.PxRect
import com.screenclicker.model.RegionClickCount
import com.screenclicker.model.Rule
import com.screenclicker.model.Script
import com.screenclicker.vision.GrayImage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque
import java.util.concurrent.CancellationException
import kotlin.random.Random

/** Integration-style tests for the real scan/cadence/click pipeline with fake screen/tap ports. */
class ScriptRunnerIntegrationTest {

    private data class Point(val x: Int, val y: Int)
    private data class RunResult(
        val taps: List<Point>,
        val detections: List<List<Pair<Int, Int>>>,
        val events: List<String>,
        val captures: Int,
    )

    private class SequenceCapturer(
        frames: List<CaptureResult>,
        private val onCapture: () -> Unit = {},
    ) : ScreenCapturer {
        private val queue = ArrayDeque(frames)
        var captures = 0
            private set
        override val name: String = "test"
        override fun isAvailable(): Boolean = true
        override suspend fun capture(): CaptureResult {
            val next = queue.pollFirst() ?: throw CancellationException("test frames exhausted")
            captures++
            onCapture()
            return next
        }
    }

    private data class Fixture(
        val width: Int,
        val height: Int,
        val template: GrayImage,
        val background: IntArray,
    ) {
        fun frame(vararg locations: Point): GrayImage {
            val pixels = background.copyOf()
            for (location in locations) {
                for (y in 0 until template.height) for (x in 0 until template.width) {
                    pixels[(location.y + y) * width + location.x + x] = template[x, y]
                }
            }
            return GrayImage(width, height, pixels)
        }

        fun capture(gray: GrayImage): CaptureResult.Success {
            val argb = IntArray(width * height) { i ->
                val value = gray.pixels[i]
                (0xFF shl 24) or (value shl 16) or (value shl 8) or value
            }
            return CaptureResult.Success(argb, width, height)
        }
    }

    private fun fixture(width: Int = 240, height: Int = 180): Fixture {
        val tw = 24
        val th = 20
        val patternRng = Random(8801)
        val template = GrayImage(tw, th, IntArray(tw * th) { patternRng.nextInt(0, 256) })
        val bgRng = Random(9902)
        val background = IntArray(width * height) { bgRng.nextInt(0, 256) }
        return Fixture(width, height, template, background)
    }

    private fun execute(
        rule: Rule,
        template: GrayImage,
        frames: List<CaptureResult>,
        settings: GlobalSettings = GlobalSettings(scanIntervalMs = 0),
        clock: () -> Long = { 10_000L },
        onCapture: () -> Unit = {},
        tapResult: (Point) -> TapOutcome = { TapOutcome.DELIVERED },
        foregroundPackage: () -> String? = { null },
        targetPackage: String? = null,
    ): RunResult {
        val capturer = SequenceCapturer(frames, onCapture)
        val taps = mutableListOf<Point>()
        val detections = mutableListOf<List<Pair<Int, Int>>>()
        val events = mutableListOf<String>()
        val runner = ScriptRunner(
            loadSettings = { settings },
            capturer = capturer,
            elapsedRealtime = clock,
            wait = {},
            writeLog = {},
        )
        try {
            runBlocking {
                runner.run(
                    script = Script(name = "test", targetPackage = targetPackage, rules = listOf(rule)),
                    templates = mapOf(rule.id to template),
                    foregroundPackage = foregroundPackage,
                    onTap = { x, y ->
                        val point = Point(x, y)
                        taps += point
                        tapResult(point)
                    },
                    onDetections = { found -> detections += found.map { it.left to it.top } },
                    onEvent = events::add,
                )
            }
        } catch (_: CancellationException) {
            // The finite fake frame source cancels the otherwise continuous service loop.
        }
        return RunResult(taps, detections, events, capturer.captures)
    }

    private fun rule(
        fixture: Fixture,
        clickMode: ClickMode = ClickMode.ON_IMAGE,
        clickRegion: PxRect? = null,
        regionClickCount: RegionClickCount = RegionClickCount.SINGLE,
        cadence: Cadence = Cadence.ONCE_PER_APPEARANCE,
        intervalMs: Long = 1_000,
        threshold: Float = 0.9f,
        searchRegion: PxRect = PxRect(0, 0, fixture.width, fixture.height),
        alignX: Int = 0,
        alignY: Int = 0,
        tapAlignX: Int = 0,
        tapAlignY: Int = 0,
        frameWidth: Int = fixture.width,
        frameHeight: Int = fixture.height,
    ) = Rule(
        id = "rule-1",
        name = "target",
        enabled = true,
        templateFile = "target.png",
        searchRegion = searchRegion,
        alignX = alignX,
        alignY = alignY,
        tapAlignX = tapAlignX,
        tapAlignY = tapAlignY,
        frameWidth = frameWidth,
        frameHeight = frameHeight,
        threshold = threshold,
        clickMode = clickMode,
        clickRegion = clickRegion,
        regionClickCount = regionClickCount,
        delayMs = 0,
        jitterMs = 0,
        cadence = cadence,
        intervalMs = intervalMs,
    )

    @Test
    fun `two 99 percent copies in a grid are both found and clicked in a fixed area`() {
        val f = fixture()
        val a = Point(24, 30)
        val b = Point(160, 112)
        val populated = f.capture(f.frame(a, b))
        val empty = f.capture(f.frame())
        val clickArea = PxRect(150, 120, 220, 175)
        val rule = rule(
            f,
            clickMode = ClickMode.ON_REGION,
            clickRegion = clickArea,
            regionClickCount = RegionClickCount.PER_OCCURRENCE,
            tapAlignX = 3,
            tapAlignY = 2,
        )

        val result = execute(rule, f.template, listOf(populated, populated, empty, populated))

        // "One per match": the first appearance clicks each occurrence; the unchanged second
        // frame does not. Both disappear and return, so both are a new appearance and get
        // clicked again.
        assertEquals(4, result.taps.size)
        assertTrue(result.taps.all { it.x in 153 until 223 && it.y in 122 until 177 })
        assertEquals(listOf(2, 2, 0, 2), result.detections.map { it.size })
        assertEquals(4, result.captures)
    }

    @Test
    fun `single area click presses the configured area once for several simultaneous finds`() {
        val f = fixture()
        val a = Point(24, 30)
        val b = Point(160, 112)
        val populated = f.capture(f.frame(a, b))
        val empty = f.capture(f.frame())
        val clickArea = PxRect(150, 120, 220, 175)
        val rule = rule(
            f,
            clickMode = ClickMode.ON_REGION,
            clickRegion = clickArea,
            regionClickCount = RegionClickCount.SINGLE,
        )

        val result = execute(rule, f.template, listOf(populated, populated, empty, populated))

        // The area is one button: two finds in the frame are one press, a still screen is
        // none, and the pair returning is one press again. Both occurrences are still found
        // and highlighted every frame.
        assertEquals(2, result.taps.size)
        assertTrue(result.taps.all { it.x in 150 until 220 && it.y in 120 until 175 })
        assertEquals(listOf(2, 2, 0, 2), result.detections.map { it.size })
    }

    @Test
    fun `single area click repeats once per interval while anything still matches`() {
        val f = fixture()
        val a = Point(30, 25)
        val b = Point(155, 110)
        val both = f.capture(f.frame(a, b))
        val onlyB = f.capture(f.frame(b))
        var time = 0L
        val result = execute(
            rule(
                f,
                clickMode = ClickMode.ON_REGION,
                clickRegion = PxRect(150, 120, 220, 175),
                regionClickCount = RegionClickCount.SINGLE,
                cadence = Cadence.REPEAT_WHILE_VISIBLE,
                intervalMs = 100,
            ),
            f.template,
            listOf(both, onlyB, onlyB, both),
            clock = { time },
            onCapture = { time += 50 },
        )

        // The rule is one switch: it presses the area at 50ms and again at 150ms, and the
        // second occurrence coming and going does not earn an extra press of its own.
        assertEquals(2, result.taps.size)
        assertEquals(listOf(2, 1, 1, 2), result.detections.map { it.size })
    }

    @Test
    fun `single area click retries a refused gesture once without pressing for the other finds`() {
        val f = fixture()
        val a = Point(30, 25)
        val b = Point(155, 110)
        var calls = 0
        val both = f.capture(f.frame(a, b))
        val result = execute(
            rule(
                f,
                clickMode = ClickMode.ON_REGION,
                clickRegion = PxRect(150, 120, 220, 175),
                regionClickCount = RegionClickCount.SINGLE,
            ),
            f.template,
            listOf(both, both, both),
            tapResult = { calls++; if (calls == 1) TapOutcome.CANCELLED else TapOutcome.DELIVERED },
        )

        // The first press is refused, so it is retried next frame; the two occurrences share
        // one press, so the retry is one tap rather than one per find.
        assertEquals(2, result.taps.size)
    }

    @Test
    fun `image click mode ignores the area click count and taps each occurrence`() {
        val f = fixture()
        val a = Point(30, 25)
        val b = Point(155, 110)
        val result = execute(
            rule(
                f,
                clickMode = ClickMode.ON_IMAGE,
                regionClickCount = RegionClickCount.SINGLE,
            ),
            f.template,
            listOf(f.capture(f.frame(a, b))),
        )

        // The setting only describes a shared area; with no area, every find taps itself.
        assertEquals(2, result.taps.size)
    }

    @Test
    fun `image click mode clicks at every detected occurrence`() {
        val f = fixture()
        val points = listOf(Point(15, 14), Point(95, 70), Point(190, 145))
        val result = execute(
            rule(f), f.template,
            listOf(f.capture(f.frame(*points.toTypedArray()))),
        )

        assertEquals(points.size, result.taps.size)
        assertEquals(points.size, result.detections.single().size)
        for (tap in result.taps) {
            assertTrue(points.any { p ->
                tap.x in (p.x + 2) until (p.x + f.template.width - 2) &&
                    tap.y in (p.y + 2) until (p.y + f.template.height - 2)
            })
        }
    }

    @Test
    fun `a tapped scan reports what it queued and each injection attempt`() {
        val f = fixture()
        val a = Point(30, 25)
        val b = Point(155, 110)
        val result = execute(rule(f), f.template, listOf(f.capture(f.frame(a, b))))

        assertEquals(2, result.taps.size)
        assertTrue(result.events.contains("Taps queued: target ×2"))
        assertEquals(
            listOf(1, 2),
            result.events.filter { it.contains("injecting tap at") }
                .map { it.substringAfter("attempt ").toInt() },
        )
    }

    @Test
    fun `the status line carries the hit count and the run-wide tap tally`() {
        val f = fixture()
        val result = execute(rule(f), f.template, listOf(f.capture(f.frame(Point(50, 50)))))

        // Exactly one status line: the fixed test clock never advances past the interval.
        val status = result.events.single { it.startsWith("scan ") }
        assertTrue(status, status.contains("hits 1, taps this run 1"))
        assertTrue(status, status.contains("last: target: match bounds="))
    }

    @Test
    fun `a declined gesture is reported with its own reason, not as cancelled`() {
        val f = fixture()
        val result = execute(
            rule(f), f.template,
            listOf(f.capture(f.frame(Point(50, 50)))),
            tapResult = { TapOutcome.DECLINED },
        )

        // A service that may not inject gestures at all is not the same fault as one that
        // was interrupted: the panel has to say which, or the diagnosis is a guess.
        assertTrue(result.events.any { it.contains("gesture declined — the system would not take it") })
        assertTrue(result.events.any { it.contains("taps this run 1") })
    }

    @Test
    fun `once-per-occurrence retries only the failed gesture`() {
        val f = fixture()
        val target = Point(70, 60)
        var count = 0
        val result = execute(
            rule(f), f.template,
            listOf(f.capture(f.frame(target)), f.capture(f.frame(target))),
            tapResult = { count++; if (count == 1) TapOutcome.CANCELLED else TapOutcome.DELIVERED },
        )

        assertEquals(2, result.taps.size)
    }

    @Test
    fun `once cadence re-clicks a location that disappeared while another stayed visible`() {
        val f = fixture()
        val a = Point(30, 25)
        val b = Point(155, 110)
        val onlyB = f.capture(f.frame(b))
        val both = f.capture(f.frame(a, b))
        val result = execute(
            rule(f), f.template,
            listOf(both, onlyB, both),
        )

        // First frame clicks A+B; B stays present on frame 2; only returning A clicks again.
        assertEquals(3, result.taps.size)
        assertEquals(listOf(2, 1, 2), result.detections.map { it.size })
    }

    @Test
    fun `a failed click retries only that occurrence without double-clicking its neighbor`() {
        val f = fixture()
        val a = Point(30, 25)
        val b = Point(155, 110)
        var calls = 0
        val both = f.capture(f.frame(a, b))
        val result = execute(
            rule(f), f.template, listOf(both, both),
            tapResult = { calls++; if (calls == 1) TapOutcome.CANCELLED else TapOutcome.DELIVERED },
        )

        // The first (A) tap is refused, B still clicks in that frame, then only A retries.
        assertEquals(3, result.taps.size)
        val inA = result.taps.count { it.x in a.x until (a.x + f.template.width) && it.y in a.y until (a.y + f.template.height) }
        val inB = result.taps.count { it.x in b.x until (b.x + f.template.width) && it.y in b.y until (b.y + f.template.height) }
        assertEquals(2, inA)
        assertEquals(1, inB)
    }

    @Test
    fun `repeat cadence clocks visible occurrences independently when another disappears`() {
        val f = fixture()
        val a = Point(30, 25)
        val b = Point(155, 110)
        val both = f.capture(f.frame(a, b))
        val onlyB = f.capture(f.frame(b))
        var time = 0L
        val result = execute(
            rule(f, cadence = Cadence.REPEAT_WHILE_VISIBLE, intervalMs = 100),
            f.template,
            listOf(both, onlyB, onlyB, both),
            clock = { time },
            onCapture = { time += 50 },
        )

        // A+B initially; B is only 50ms later; B repeats at 100ms; returning A is immediately eligible.
        assertEquals(4, result.taps.size)
        assertEquals(listOf(2, 1, 1, 2), result.detections.map { it.size })
    }

    @Test
    fun `rule alignment translates the search region and tap alignment translates highlights`() {
        val f = fixture()
        val target = Point(42, 45)
        val rule = rule(
            f,
            searchRegion = PxRect(20, 20, 150, 120),
            alignX = 5,
            alignY = 7,
            tapAlignX = 9,
            tapAlignY = 11,
        )
        val result = execute(rule, f.template, listOf(f.capture(f.frame(target))))

        assertEquals(listOf(target.x + 4 to target.y + 4), result.detections.single())
        assertEquals(1, result.taps.size)
        assertTrue(result.taps.single().x in (target.x + 6) until (target.x + f.template.width + 2))
        assertTrue(result.taps.single().y in (target.y + 6) until (target.y + f.template.height + 2))
    }

    @Test
    fun `template missing from the decoded map and undersized region are reported safely`() {
        val f = fixture()
        val events = mutableListOf<String>()
        val runner = ScriptRunner({ GlobalSettings(scanIntervalMs = 0) }, SequenceCapturer(listOf(f.capture(f.frame()))), { 10_000L }, {}, {})
        try {
            runBlocking {
                runner.run(Script(rules = listOf(rule(f))), emptyMap(), { null }, { _, _ -> TapOutcome.DECLINED }, onEvent = events::add)
            }
        } catch (_: CancellationException) {}
        assertTrue(events.any { it.contains("template could not be read") })

        val small = rule(f, searchRegion = PxRect(0, 0, 10, 10))
        val result = execute(small, f.template, listOf(f.capture(f.frame(Point(50, 50)))))
        assertTrue(result.taps.isEmpty())
        assertTrue(result.events.any { it.contains("search area 10x10 is smaller") })
    }

    @Test
    fun `featureless template produces a no-contrast status and no clicks`() {
        val f = fixture()
        val flat = GrayImage(24, 20, IntArray(24 * 20) { 127 })
        val result = execute(rule(f), flat, listOf(f.capture(f.frame(Point(50, 50)))))
        assertTrue(result.taps.isEmpty())
        assertTrue(result.events.any { it.contains("template has no contrast") })
    }

    @Test
    fun `capture dimension mismatch reports the reason and does not click`() {
        val f = fixture()
        val rule = rule(f, frameWidth = f.width + 1).copy(captureBackend = "projection")
        val result = execute(rule, f.template, listOf(f.capture(f.frame(Point(50, 50)))))

        assertTrue(result.taps.isEmpty())
        assertTrue(result.events.any { it.contains("Capture space mismatch") && it.contains("projection") })
    }

    @Test
    fun `threshold excludes otherwise exact occurrences`() {
        val f = fixture()
        val result = execute(
            rule(f, threshold = 1.01f), f.template,
            listOf(f.capture(f.frame(Point(50, 50)))),
        )

        assertTrue(result.taps.isEmpty())
        assertEquals(0, result.detections.single().size)
    }

    @Test
    fun `target app binding skips capture until the configured package is foreground`() {
        val f = fixture()
        var foregroundChecks = 0
        val result = execute(
            rule(f), f.template,
            listOf(f.capture(f.frame(Point(50, 50)))),
            foregroundPackage = {
                foregroundChecks++
                if (foregroundChecks == 1) "other.app" else "target.app"
            },
            targetPackage = "target.app",
        )
        assertEquals("detections=${result.detections}", 1, result.taps.size)
        assertEquals(1, result.captures)
        assertTrue(foregroundChecks >= 2)
    }

    @Test
    fun `rules without templates and disabled rules do not start scanning`() {
        val noTemplate = Rule(id = "empty", enabled = true, templateFile = null)
        val disabled = Rule(id = "disabled", enabled = false, templateFile = "x.png")
        val runner = ScriptRunner({ GlobalSettings() }, SequenceCapturer(emptyList()), { 10_000L }, {}, {})
        val events = mutableListOf<String>()
        runBlocking {
            runner.run(Script(rules = listOf(noTemplate, disabled)), emptyMap(), { null }, { _, _ -> TapOutcome.DECLINED }, onEvent = events::add)
        }
        assertTrue(events.single().contains("No enabled rules"))
    }

    @Test
    fun `failed capture is reported and does not produce clicks`() {
        val f = fixture()
        val result = execute(
            rule(f), f.template,
            listOf(CaptureResult.Failure("camera busy")),
        )
        assertTrue(result.taps.isEmpty())
        assertTrue(result.events.any { it.contains("Capture failed: camera busy") })
    }
}

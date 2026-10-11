package com.screenclicker.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The run overlay's two switches, and the mute that our own windows use.
 *
 * The case that used to be wrong is [taps on with the match boxes off]: the window must stay
 * up (the markers need it) while the rectangle layer must not draw.
 */
class OverlayLayersTest {

    @Test
    fun `nothing switched on leaves the window hidden`() {
        val layers = RunOverlayLayers.of(detections = false, taps = false, muted = false)
        assertEquals(RunOverlayLayers.NONE, layers)
    }

    @Test
    fun `each switch on its own keeps the window up and draws only its own layer`() {
        val boxes = RunOverlayLayers.of(detections = true, taps = false, muted = false)
        assertTrue(boxes.windowVisible)
        assertTrue(boxes.matches)
        assertFalse(boxes.taps)

        val markers = RunOverlayLayers.of(detections = false, taps = true, muted = false)
        assertTrue(markers.windowVisible)
        assertFalse("the window is up for the markers, not for boxes", markers.matches)
        assertTrue(markers.taps)
    }

    @Test
    fun `both switches on draws both`() {
        val layers = RunOverlayLayers.of(detections = true, taps = true, muted = false)
        assertTrue(layers.windowVisible)
        assertTrue(layers.matches)
        assertTrue(layers.taps)
    }

    @Test
    fun `muting hides the window and every layer, whatever the switches say`() {
        val layers = RunOverlayLayers.of(detections = true, taps = true, muted = true)
        assertFalse(layers.windowVisible)
        assertFalse(layers.matches)
        assertFalse(layers.taps)
    }

    @Test
    fun `unmuting restores the switches rather than switching anything on`() {
        // A capture mutes and un-mutes while a run is going; a user who turned the boxes off
        // must not get them back because a capture happened.
        val off = RunOverlayLayers.of(detections = false, taps = true, muted = true)
        val back = RunOverlayLayers.of(detections = false, taps = true, muted = false)
        assertFalse(off.matches)
        assertFalse(back.matches)
        assertTrue(back.taps)
    }
}
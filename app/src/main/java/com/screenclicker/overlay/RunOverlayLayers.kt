package com.screenclicker.overlay

/**
 * What the run overlay is allowed to draw, and whether its window is on screen at all.
 *
 * Two independent switches — match rectangles, tap markers — plus one input that is not the
 * user's: [muted], which our own windows set while they need the layer out of the way (a
 * template capture, the installer, the capture-consent dialog).
 *
 * The window has to stay *visible* while either switch has something to draw: turning the
 * rectangles off must not take the markers down with them, and vice versa. That is exactly
 * why the switches have to be enforced **per layer** rather than only on the window. With
 * markers on and rectangles off the window is legitimately visible, so a rectangle loop that
 * only checked the window's visibility keeps painting boxes the user switched off — which is
 * the bug this type exists to make impossible: "Highlight matches" was off, the tap layer was
 * on, and the match boxes were still on screen (round 19).
 *
 * [matches] and [taps] are false while muted as well, so a muted layer cannot paint even in
 * the frames between the mute and the window's own visibility flip.
 */
data class RunOverlayLayers(
    val windowVisible: Boolean,
    val matches: Boolean,
    val taps: Boolean,
) {
    companion object {
        /** Hidden window, both layers off: the state before either switch is on. */
        val NONE = RunOverlayLayers(windowVisible = false, matches = false, taps = false)

        fun of(detections: Boolean, taps: Boolean, muted: Boolean): RunOverlayLayers {
            val drawing = !muted
            return RunOverlayLayers(
                windowVisible = (detections || taps) && drawing,
                matches = detections && drawing,
                taps = taps && drawing,
            )
        }
    }
}
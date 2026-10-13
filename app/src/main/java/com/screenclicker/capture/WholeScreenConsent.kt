package com.screenclicker.capture

import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build

/**
 * The screen-capture consent dialog, asked for the whole display.
 *
 * Android 14 added "share a single app" and made it the dialog's *default* selection. That
 * is the wrong default for this app twice over: the runner works in display coordinates —
 * every rule's region, every template crop and every tap point is a point on the display —
 * and a single-app projection is that one app's window, letterboxed inside a display-sized
 * frame, so the pixels the matcher sees are offset and scaled away from the coordinates it
 * is searching in. The run then finds nothing and clicks nothing, with no error to say why.
 *
 * `createConfigForDefaultDisplay()` is the request that says "the default display, not a
 * window": on API 34+ the dialog comes up asking for the whole screen instead of offering
 * the single-app picker. The no-argument overload is the *user choice* variant, which is
 * what produced the single-app default. Below API 34 there is no single-app option to
 * avoid, so the plain intent is the same thing.
 *
 * Both ways into the dialog go through here — the app's own button and the invisible
 * activity a running script launches — so the two cannot ask for different things.
 */
fun MediaProjectionManager.consentIntentForWholeScreen(): Intent =
    if (Build.VERSION.SDK_INT >= 34) {
        createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
    } else {
        createScreenCaptureIntent()
    }

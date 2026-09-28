package com.screenclicker.capture

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.CompletableDeferred

/**
 * Invisible trampoline for the system's screen-capture consent dialog.
 *
 * `MediaProjectionManager.createScreenCaptureIntent()` can only be answered by an
 * Activity — the result comes back through `onActivityResult`, which a Service has no
 * way to receive. So when a script is started from the floating panel or the rule
 * editor captures the screen while no capture session is live, this activity is
 * launched from the background (permitted for apps with the overlay permission, which
 * Screen Clicker always has), shows nothing itself, and puts the system's dialog on
 * top. A grant starts [CaptureProjectionService] exactly like the manual grant in the
 * app's settings; a denial simply completes [request] with false.
 *
 * [request] is a suspend function so callers can block on the user's answer without
 * wiring callbacks through three services. Concurrent requests collapse into the one
 * dialog that is actually on screen.
 */
class CaptureConsentActivity : Activity() {

    companion object {
        private const val TAG = "CaptureConsent"
        private const val REQUEST_CODE = 9001

        /** The dialog currently on screen, if any. */
        private var inFlight: CompletableDeferred<Boolean>? = null

        /**
         * Shows the system consent dialog and returns the user's answer. Safe to call
         * from any context (it starts the activity with NEW_TASK) and from any thread.
         */
        suspend fun request(context: Context): Boolean {
            inFlight?.let { return it.await() }
            val deferred = CompletableDeferred<Boolean>()
            inFlight = deferred
            return try {
                context.startActivity(
                    Intent(context, CaptureConsentActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                )
                deferred.await()
            } catch (e: Exception) {
                Log.w(TAG, "could not show the capture consent dialog", e)
                inFlight = null
                false
            }
        }

        /** Idempotent: the losing completion (onDestroy after onActivityResult) is a no-op. */
        private fun complete(granted: Boolean) {
            inFlight?.complete(granted)
            inFlight = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Translucent theme: the user sees only the system's dialog over whatever app
        // they were in. Nothing here must be visible.
        val manager = getSystemService(MediaProjectionManager::class.java)
        if (manager == null) {
            Log.w(TAG, "no MediaProjectionManager; denying")
            complete(false)
            finish()
            return
        }
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CODE) return
        if (resultCode == RESULT_OK && data != null) {
            // Same path as the manual grant: the projection service must already be a
            // mediaProjection foreground service when getMediaProjection() is called
            // (Android 14+), which its start() takes care of.
            CaptureProjectionService.start(this, resultCode, data)
            Log.i(TAG, "screen capture granted at the moment it was needed")
            complete(true)
        } else {
            Log.i(TAG, "screen capture denied")
            complete(false)
        }
        finish()
    }

    override fun onDestroy() {
        // If the activity dies without an answer (user backed out of a weird launcher,
        // system kill), nobody must be left waiting on the deferred.
        complete(false)
        super.onDestroy()
    }
}

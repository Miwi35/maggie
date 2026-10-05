package com.maggie.app.voice

import android.util.Log

object AssistantLauncher {

    private const val TAG = "AssistantLauncher"

    /**
     * Opens the assistant from the background. The system-bound voice interaction
     * session is the only launch Android lets through when the app is not visible
     * (and over the lock screen); the plain activity start is a best-effort fallback
     * for when Maggie is not the default assistant.
     */
    fun launch(showSession: () -> Boolean, startActivity: () -> Unit) {
        val shown = try {
            showSession()
        } catch (e: Exception) {
            Log.w(TAG, "showSession failed: ${e.message}")
            false
        }
        if (!shown) startActivity()
    }
}

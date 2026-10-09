package com.maggie.app.e2e

import android.content.Intent

/**
 * The Maestro flow driving the app right now, as its path in the repository
 * (`e2e/mobile/flows/01-login-chat.yaml`).
 *
 * Every flow hands it over at each `launchApp` (`arguments: { e2e_journey: … }`, which
 * Maestro turns into an intent extra) and `e2e/mobile/lint.sh` checks that the value is
 * the flow's own path. [E2eHooks] reads it off every activity that starts, and
 * `JourneyHeaderPlugin` sends it with every request.
 *
 * Process-wide and kept until another launch names another flow: a deep link opened in
 * the middle of a flow carries no extra, and is still that flow's. Gone with the process
 * — which is why a flow that stops the app names itself again when it relaunches it.
 */
object E2eJourney {
    const val EXTRA = "e2e_journey"
    const val HEADER = "X-E2E-Journey"

    @Volatile
    var id: String? = null
        private set

    /** Takes the journey an intent names, if it names one. */
    fun readFrom(intent: Intent?) {
        intent?.getStringExtra(EXTRA)?.trim()?.takeIf { it.isNotEmpty() }?.let { id = it }
    }

    /** For the tests: back to no journey. */
    internal fun reset() {
        id = null
    }
}

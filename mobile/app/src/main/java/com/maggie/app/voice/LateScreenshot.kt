package com.maggie.app.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The session's word to the open overlay that the screenshot it opened without is
 * now in [ScreenshotEncoder.file] (MAG-214).
 *
 * Not an intent: the overlay is `singleTask`, and a new intent means « listen
 * again », which would cut the user off mid-sentence. The session and the overlay
 * share the process, so a flag is enough — a state and not an event, because the
 * screenshot can land before the overlay has started collecting.
 */
object LateScreenshot {
    private val arrived = MutableStateFlow(false)
    val hasArrived: StateFlow<Boolean> = arrived.asStateFlow()

    /** A new overlay is about to open with a screenshot on its way: no earlier one counts. */
    fun expect() {
        arrived.value = false
    }

    fun arrive() {
        arrived.value = true
    }

    /** The overlay took it into its context, or threw it away. */
    fun consume() {
        arrived.value = false
    }
}

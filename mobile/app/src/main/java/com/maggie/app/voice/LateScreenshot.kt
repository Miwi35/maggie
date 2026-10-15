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

    /** Why it will not come, when it will not: the overlay shows it instead of waiting forever. */
    private val missed = MutableStateFlow<ScreenshotMiss?>(null)
    val hasMissed: StateFlow<ScreenshotMiss?> = missed.asStateFlow()

    /** A new overlay is about to open with a screenshot on its way: no earlier one counts. */
    fun expect() {
        consume()
    }

    fun arrive() {
        arrived.value = true
    }

    fun miss(reason: ScreenshotMiss) {
        missed.value = reason
    }

    /** The overlay took it into its context, threw it away, or took note it is not coming. */
    fun consume() {
        arrived.value = false
        missed.value = null
    }
}

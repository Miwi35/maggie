package com.maggie.app.voice

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Whether the overlay's next sentence carries the screenshot (MAG-486). Nothing is attached
 * when the overlay opens: the capture stays on the phone until the user touches « + Écran »,
 * and the cross takes it back. [region] is the zone they circled on it (MAG-316).
 */
class ScreenAttachment(private val screenshot: PendingScreenshot) {

    var attached by mutableStateOf(false)
        private set

    var region by mutableStateOf<CropRegion?>(null)
        private set

    fun attach() {
        attached = true
    }

    /** The cross: nothing goes with the sentence, and a zone circled on it is forgotten. */
    fun remove() {
        attached = false
        region = null
    }

    fun select(zone: CropRegion?) {
        region = zone
    }

    /** The image for the sentence being sent: null unless it was attached and [context] has one. */
    fun take(context: ScreenContext?): ByteArray? =
        if (attached && context?.hasScreenshot == true) screenshot.take(region) else null

    /** A new invocation: the previous screen's attachment and zone mean nothing on this one. */
    fun reset() {
        attached = false
        region = null
    }

    /** The sentence left: the capture is deleted whether it went with it or not. */
    fun sent() {
        reset()
        screenshot.discard()
    }
}

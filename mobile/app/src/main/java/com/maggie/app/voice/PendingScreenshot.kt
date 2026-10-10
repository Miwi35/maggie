package com.maggie.app.voice

import java.io.File

/**
 * The assist screenshot waiting in [file] for the overlay's next sentence (MAG-214).
 *
 * Kept nowhere longer than needed: read once and deleted when a sentence takes it,
 * deleted unread when the overlay closes or another invocation replaces it. The
 * file is always [ScreenshotEncoder.file], never a path from an intent.
 */
class PendingScreenshot(private val file: File) {

    /** The bytes to send, once: the file is gone afterwards, read or not. Null when there is none. */
    fun take(): ByteArray? {
        if (!file.exists()) return null
        val bytes = runCatching { file.readBytes() }.getOrNull()
        file.delete()
        return bytes
    }

    /** The user did not send it: it is not kept. */
    fun discard() {
        file.delete()
    }
}

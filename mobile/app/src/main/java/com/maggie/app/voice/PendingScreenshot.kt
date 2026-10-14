package com.maggie.app.voice

import java.io.File

/**
 * The assist screenshot waiting in [file] for the overlay's next sentence (MAG-214).
 *
 * Kept nowhere longer than needed: read once and deleted when a sentence takes it,
 * deleted unread when the overlay closes or another invocation replaces it. The
 * file is always [ScreenshotEncoder.file], never a path from an intent.
 */
class PendingScreenshot(
    private val file: File,
    private val crop: (ByteArray, CropRegion) -> ByteArray? = ScreenshotEncoder::crop,
) {

    /**
     * The bytes to send, once: the file is gone afterwards, read or not. Null when there is none.
     * With a [region] the user circled (MAG-316) only that zone is returned — and nothing at all
     * if it cannot be cut, never the whole screen they chose not to send.
     */
    fun take(region: CropRegion? = null): ByteArray? {
        if (!file.exists()) return null
        val bytes = runCatching { file.readBytes() }.getOrNull()
        file.delete()
        if (bytes == null || region == null) return bytes
        return runCatching { crop(bytes, region) }.getOrNull()
    }

    /** The user did not send it: it is not kept. */
    fun discard() {
        file.delete()
    }
}

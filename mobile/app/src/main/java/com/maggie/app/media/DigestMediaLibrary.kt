package com.maggie.app.media

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.maggie.app.data.model.ChatMessage
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** Where the media tree reads the digest from; the API in the app, a fake in the tests. */
interface DigestSource {
    /** The newest voice message of the digest, whatever its day; null when there is none. */
    suspend fun latestVoice(): ChatMessage?

    /** The short signed URL of the voice, asked for when it is played. */
    suspend fun signedUrl(messageId: String): String
}

/**
 * What Android Auto lists under Maggie (MAG-773): one root, one playable entry — the last digest's voice.
 * Android Auto draws the screen; this gives it the content, as the mockup says.
 */
class DigestMediaLibrary(
    private val source: DigestSource,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val artwork: () -> ByteArray? = { null },
) {
    fun root(): MediaItem = MediaItem.Builder()
        .setMediaId(ROOT_ID)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(ROOT_TITLE)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                .build(),
        )
        .build()

    /** The root's children: the last digest voice, or nothing. A failing call is an empty list, never an error in the car. */
    suspend fun children(): List<MediaItem> = listOfNotNull(latest())

    suspend fun latest(): MediaItem? =
        runCatching { source.latestVoice() }.getOrNull()?.takeIf { it.audio != null }?.let(::entry)

    /** [item] with the signed URL to read; null when it is not the digest's or the URL cannot be had. */
    suspend fun playable(item: MediaItem): MediaItem? {
        val id = messageIdOf(item.mediaId) ?: return null
        val url = runCatching { source.signedUrl(id) }.getOrNull() ?: return null
        return item.buildUpon().setUri(Uri.parse(url)).setMimeType("audio/mpeg").build()
    }

    private fun entry(message: ChatMessage): MediaItem {
        val date = dateLabel(message.createdAt, zone())
        val durationMs = message.audio?.durationMs ?: 0
        val metadata = MediaMetadata.Builder()
            .setTitle(TITLE)
            .setSubtitle(subtitle(date, durationMs))
            .setArtist(listOfNotNull(ROOT_TITLE, date).joinToString(" · "))
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER)
        if (durationMs > 0) metadata.setDurationMs(durationMs.toLong())
        artwork()?.let { metadata.setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER) }
        return MediaItem.Builder().setMediaId(ID_PREFIX + message.id).setMediaMetadata(metadata.build()).build()
    }

    companion object {
        const val ROOT_ID = "maggie-root"
        const val ROOT_TITLE = "Maggie"
        const val TITLE = "Digest du matin"
        const val ID_PREFIX = "digest:"

        private val DATE_FORMAT = DateTimeFormatter.ofPattern("EEEE d MMM", Locale.FRENCH)

        fun messageIdOf(mediaId: String): String? = mediaId.removePrefix(ID_PREFIX).takeIf { mediaId.startsWith(ID_PREFIX) && it.isNotBlank() }

        /** « mardi 10 nov. » — the day the message was written; null when the date cannot be read. */
        fun dateLabel(createdAt: String, zone: ZoneId): String? =
            runCatching { OffsetDateTime.parse(createdAt).atZoneSameInstant(zone).format(DATE_FORMAT) }.getOrNull()

        /** « mardi 10 nov. · 3 min »: the duration rounded to the minute, one at least. */
        fun subtitle(date: String?, durationMs: Int): String {
            val minutes = (durationMs / 60_000f).roundToInt().coerceAtLeast(1)
            return listOfNotNull(date, "$minutes min").joinToString(" · ")
        }
    }
}

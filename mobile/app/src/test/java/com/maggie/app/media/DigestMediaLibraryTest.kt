package com.maggie.app.media

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.AudioAttachment
import com.maggie.app.data.model.ChatMessage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class DigestMediaLibraryTest {

    private class FakeSource(var voice: ChatMessage?, var url: String? = "https://signed.example/a.mp3") : DigestSource {
        var urlAsked = 0
        override suspend fun latestVoice() = voice
        override suspend fun signedUrl(messageId: String): String {
            urlAsked++
            return url ?: error("down")
        }
    }

    private val tuesday = ChatMessage(
        id = "m-2",
        role = "assistant",
        content = "Bonjour",
        createdAt = "2026-11-10T06:30:00+00:00",
        audio = AudioAttachment(durationMs = 175_000),
    )

    private fun library(source: DigestSource) = DigestMediaLibrary(source, zone = { ZoneId.of("UTC") })

    @Test
    fun `the root is Maggie and offers one entry, the last digest`() = runBlocking {
        val library = library(FakeSource(tuesday))

        assertEquals("Maggie", library.root().mediaMetadata.title.toString())
        val children = library.children()

        assertEquals(1, children.size)
        val entry = children.single()
        assertEquals("digest:m-2", entry.mediaId)
        assertEquals("Digest du matin", entry.mediaMetadata.title.toString())
        assertEquals("mardi 10 nov. · 3 min", entry.mediaMetadata.subtitle.toString())
        assertEquals("Maggie · mardi 10 nov.", entry.mediaMetadata.artist.toString())
        assertEquals(true, entry.mediaMetadata.isPlayable)
    }

    @Test
    fun `no digest voice gives an empty list`() = runBlocking {
        assertTrue(library(FakeSource(null)).children().isEmpty())
    }

    @Test
    fun `a message without audio is not offered`() = runBlocking {
        assertTrue(library(FakeSource(tuesday.copy(audio = null))).children().isEmpty())
    }

    @Test
    fun `an API failure is an empty list, not an error`() = runBlocking {
        val failing = object : DigestSource {
            override suspend fun latestVoice(): ChatMessage? = error("offline")
            override suspend fun signedUrl(messageId: String) = error("offline")
        }

        assertTrue(library(failing).children().isEmpty())
    }

    @Test
    fun `the signed URL is asked for only when playing`() = runBlocking {
        val source = FakeSource(tuesday)
        val library = library(source)

        val entry = library.children().single()
        assertEquals(0, source.urlAsked)

        val playable = library.playable(entry)
        assertEquals("https://signed.example/a.mp3", playable?.localConfiguration?.uri.toString())
        assertEquals(1, source.urlAsked)
    }

    @Test
    fun `an unknown id or a signing failure plays nothing`() = runBlocking {
        val library = library(FakeSource(tuesday, url = null))
        val entry = library.children().single()

        assertNull(library.playable(entry))
        assertNull(library.playable(entry.buildUpon().setMediaId("other:1").build()))
    }

    @Test
    fun `the duration is rounded to the minute, one at least`() {
        assertEquals("mardi 10 nov. · 1 min", DigestMediaLibrary.subtitle("mardi 10 nov.", 20_000))
        assertEquals("3 min", DigestMediaLibrary.subtitle(null, 180_000))
        assertNotNull(DigestMediaLibrary.messageIdOf("digest:abc"))
        assertNull(DigestMediaLibrary.messageIdOf("digest:"))
    }
}

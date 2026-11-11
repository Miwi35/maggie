package com.maggie.app.media

import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.AudioAttachment
import com.maggie.app.data.model.ChatMessage
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ExecutionException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class DigestSessionCallbackTest {

    private val source = object : DigestSource {
        override suspend fun latestVoice() = ChatMessage(
            id = "m-2", role = "assistant", content = "Bonjour", createdAt = "2026-11-10T06:30:00+00:00",
            audio = AudioAttachment(durationMs = 180_000),
        )

        override suspend fun signedUrl(messageId: String) = "https://signed.example/$messageId.mp3"
    }
    private val callback = DigestSessionCallback(DigestMediaLibrary(source), TestScope(UnconfinedTestDispatcher()))
    private val session = mockk<MediaLibrarySession>(relaxed = true)
    private val controller = mockk<MediaSession.ControllerInfo>(relaxed = true)

    @Test
    fun `the tree has one root and one entry`() {
        val root = callback.onGetLibraryRoot(session, controller, null).get().value!!
        val children = callback.onGetChildren(session, controller, root.mediaId, 0, 10, null).get().value!!

        assertEquals(listOf("digest:m-2"), children.map { it.mediaId })
    }

    @Test
    fun `an entry has no children`() {
        val children = callback.onGetChildren(session, controller, "digest:m-2", 0, 10, null).get().value!!

        assertEquals(0, children.size)
    }

    @Test
    fun `a play request gets the signed URL`() {
        val entry = callback.onGetChildren(session, controller, DigestMediaLibrary.ROOT_ID, 0, 10, null).get().value!!.single()

        val items = callback.onAddMediaItems(session, controller, mutableListOf(entry)).get()

        assertEquals("https://signed.example/m-2.mp3", items.single().localConfiguration?.uri.toString())
    }

    @Test
    fun `a resumption plays nothing by itself`() {
        assertThrows(ExecutionException::class.java) { callback.onPlaybackResumption(session, controller).get() }
    }
}

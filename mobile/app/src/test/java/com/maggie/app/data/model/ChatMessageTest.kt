package com.maggie.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatMessageTest {

    private val audio = AudioAttachment(transcript = "Ta journée.")

    @Test
    fun `without audio there is no text beside a voice`() {
        assertNull(ChatMessage(id = "1", role = "assistant", content = "Bonjour.").textBesideVoice)
    }

    @Test
    fun `a text that is the transcript is not repeated beside the voice`() {
        val message = ChatMessage(id = "1", role = "assistant", content = " [warmly] Ta journée. ", audio = audio)

        assertNull(message.textBesideVoice)
    }

    @Test
    fun `an announcement is shown beside the voice, without its tags`() {
        val message = ChatMessage(id = "1", role = "assistant", content = "[warmly] Votre digest est là.", audio = audio)

        assertEquals("Votre digest est là.", message.textBesideVoice)
    }
}

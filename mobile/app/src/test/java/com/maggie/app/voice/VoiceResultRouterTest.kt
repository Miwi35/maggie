package com.maggie.app.voice

import com.maggie.app.ui.screens.chat.ChatViewModel
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceResultRouterTest {

    private val chat = mockk<ChatViewModel>(relaxed = true)
    private val voice = mockk<VoiceManager>(relaxed = true)

    @Test
    fun `a spoken answer decides the card, frees the microphone and sends nothing`() {
        every { chat.answerApprovalByVoice("oui") } returns true

        val contextUsed = routeVoiceResult("oui", chat, voice, null)

        assertFalse(contextUsed)
        verify(exactly = 1) { voice.answerHandled() }
        verify(exactly = 0) { chat.sendMessage(any(), any()) }
    }

    @Test
    fun `any other sentence is a message for Maggie and the microphone is left to the reply`() {
        every { chat.answerApprovalByVoice("oui mais attends") } returns false

        val contextUsed = routeVoiceResult("oui mais attends", chat, voice, null)

        assertTrue(contextUsed)
        verify(exactly = 1) { chat.sendMessage("oui mais attends", null) }
        verify(exactly = 0) { voice.answerHandled() }
    }
}

package com.maggie.app.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.PendingApproval
import com.maggie.app.screentest.FakeChat
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import com.maggie.app.voice.VoiceManager
import com.maggie.app.voice.VoiceState
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The held actions in the assistant overlay (MAG-310): the card is there, a touch answers it,
 * and Maggie asks the question aloud once, when nothing else is being said.
 */
@RunWith(AndroidJUnit4::class)
class AssistantOverlayScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private val approval = PendingApproval(
        id = "ap-1",
        toolName = "delete_event",
        arguments = buildJsonObject { put("title", "Test validation") },
        createdAt = "2026-10-07T08:00:00Z",
    )

    private fun voice(state: VoiceState = VoiceState.IDLE): VoiceManager {
        val manager = mockk<VoiceManager>(relaxed = true)
        every { manager.state } returns MutableStateFlow(state)
        every { manager.duration } returns MutableStateFlow(0)
        every { manager.errorMessage } returns MutableStateFlow(null)
        every { manager.holdHint } returns MutableStateFlow(false)
        every { manager.handsFree } returns MutableStateFlow(false)
        every { manager.partialText } returns MutableStateFlow("")
        return manager
    }

    private fun show(chat: FakeChat, voiceManager: VoiceManager) = compose.setContent {
        AssistantOverlay(
            viewModel = chat.viewModel,
            voiceManager = voiceManager,
            onDismiss = {},
            onVoiceResult = {},
        )
    }

    @Test
    fun `a pending action shows its card in the overlay`() {
        show(FakeChat(history = emptyList(), pending = listOf(approval)), voice())
        compose.waitForIdle()

        compose.onNodeWithText("Maggie demande ton accord").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.approvalAllow("ap-1")).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.approvalDeny("ap-1")).assertIsDisplayed()
    }

    @Test
    fun `Autoriser answers the card and it leaves the overlay`() {
        val chat = FakeChat(history = emptyList(), pending = listOf(approval))
        show(chat, voice())
        compose.waitForIdle()

        compose.onNodeWithTag(UiTags.approvalAllow("ap-1")).performClick()
        compose.waitForIdle()

        assertEquals(listOf("approve:ap-1"), chat.decisions)
        compose.onNodeWithTag(UiTags.approvalCard("ap-1")).assertDoesNotExist()
    }

    @Test
    fun `Refuser answers the card and it leaves the overlay`() {
        val chat = FakeChat(history = emptyList(), pending = listOf(approval))
        show(chat, voice())
        compose.waitForIdle()

        compose.onNodeWithTag(UiTags.approvalDeny("ap-1")).performClick()
        compose.waitForIdle()

        assertEquals(listOf("deny:ap-1"), chat.decisions)
        compose.onNodeWithTag(UiTags.approvalCard("ap-1")).assertDoesNotExist()
    }

    @Test
    fun `Maggie asks the question aloud, once`() {
        val manager = voice()
        show(FakeChat(history = emptyList(), pending = listOf(approval)), manager)
        compose.waitForIdle()

        verify(exactly = 1) { manager.speak("Je supprime l'événement Test validation ?") }
    }

    @Test
    fun `Maggie does not talk over someone already speaking or listening`() {
        val manager = voice(VoiceState.LISTENING)
        show(FakeChat(history = emptyList(), pending = listOf(approval)), manager)
        compose.waitForIdle()

        verify(exactly = 0) { manager.speak(any()) }
    }

    @Test
    fun `no pending action, no card and nothing to ask`() {
        val manager = voice()
        show(FakeChat(history = emptyList()), manager)
        compose.waitForIdle()

        compose.onNodeWithText("Maggie demande ton accord").assertDoesNotExist()
        verify(exactly = 0) { manager.speak(any()) }
    }
}

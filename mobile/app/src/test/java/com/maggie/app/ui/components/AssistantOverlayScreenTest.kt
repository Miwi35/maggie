package com.maggie.app.ui.components

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.PendingApproval
import com.maggie.app.screentest.FakeChat
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import com.maggie.app.voice.VoiceManager
import com.maggie.app.voice.VoiceState
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    private class Voice(state: VoiceState = VoiceState.IDLE, handsFree: Boolean = false) {
        val state = MutableStateFlow(state)
        val manager = mockk<VoiceManager>(relaxed = true).also { manager ->
            every { manager.state } returns this.state
            every { manager.duration } returns MutableStateFlow(0)
            every { manager.errorMessage } returns MutableStateFlow(null)
            every { manager.holdHint } returns MutableStateFlow(false)
            every { manager.handsFree } returns MutableStateFlow(handsFree)
            every { manager.partialText } returns MutableStateFlow("")
        }
    }

    private fun show(chat: FakeChat, voice: Voice, onListen: () -> Unit = {}) = compose.setContent {
        AssistantOverlay(
            viewModel = chat.viewModel,
            voiceManager = voice.manager,
            onDismiss = {},
            onVoiceResult = {},
            onListen = onListen,
        )
    }

    // The sheet is one clickable surface, so the card's own node is merged away: the Autoriser
    // button, which keeps its own node, is what tells the card is still on screen. The sheet sits
    // at the bottom of a window Robolectric makes too small for a touch to reach it, hence the
    // click as a semantic action; the view model's answer arrives on its own coroutine, so wait
    // for the card to leave.
    private fun answer(tag: String) {
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag(UiTags.approvalAllow("ap-1")).fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun `a pending action shows its card in the overlay`() {
        show(FakeChat(history = emptyList(), pending = listOf(approval)), Voice())
        compose.waitForIdle()

        compose.onNodeWithText("Maggie demande ton accord").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.approvalAllow("ap-1")).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.approvalDeny("ap-1")).assertIsDisplayed()
    }

    @Test
    fun `Autoriser answers the card and it leaves the overlay`() {
        val chat = FakeChat(history = emptyList(), pending = listOf(approval))
        show(chat, Voice())
        compose.waitForIdle()

        answer(UiTags.approvalAllow("ap-1"))
        coVerify(exactly = 1) { chat.approvals.approve("ap-1") }
    }

    @Test
    fun `Refuser answers the card and it leaves the overlay`() {
        val chat = FakeChat(history = emptyList(), pending = listOf(approval))
        show(chat, Voice())
        compose.waitForIdle()

        answer(UiTags.approvalDeny("ap-1"))
        coVerify(exactly = 1) { chat.approvals.deny("ap-1") }
    }

    @Test
    fun `Maggie asks the question aloud, once, and marks the card as read out`() {
        val voice = Voice()
        val chat = FakeChat(history = emptyList(), pending = listOf(approval))
        show(chat, voice)
        compose.waitForIdle()

        verify(exactly = 1) { voice.manager.speak("Je supprime l'événement Test validation ?") }
        assertTrue(chat.viewModel.isApprovalAsked("ap-1"))
    }

    @Test
    fun `the listening that opens with the overlay is cut for the question, then reopened`() {
        val voice = Voice(VoiceState.LISTENING, handsFree = true)
        var reopened = 0
        show(FakeChat(history = emptyList(), pending = listOf(approval)), voice) { reopened++ }
        compose.waitForIdle()

        verify(exactly = 1) { voice.manager.cancelListening() }
        verify(exactly = 1) { voice.manager.speak("Je supprime l'événement Test validation ?") }
        assertEquals(0, reopened)

        voice.state.value = VoiceState.SPEAKING
        compose.waitForIdle()
        voice.state.value = VoiceState.IDLE
        compose.waitForIdle()

        assertEquals(1, reopened)
    }

    @Test
    fun `Maggie does not cut someone who is holding the mic`() {
        val voice = Voice(VoiceState.LISTENING, handsFree = false)
        show(FakeChat(history = emptyList(), pending = listOf(approval)), voice)
        compose.waitForIdle()

        verify(exactly = 0) { voice.manager.speak(any()) }
        verify(exactly = 0) { voice.manager.cancelListening() }
    }

    @Test
    fun `Maggie does not talk over a reply being read`() {
        val voice = Voice(VoiceState.SPEAKING)
        show(FakeChat(history = emptyList(), pending = listOf(approval)), voice)
        compose.waitForIdle()

        verify(exactly = 0) { voice.manager.speak(any()) }
    }

    @Test
    fun `no pending action, no card and nothing to ask`() {
        val voice = Voice()
        show(FakeChat(history = emptyList()), voice)
        compose.waitForIdle()

        compose.onNodeWithText("Maggie demande ton accord").assertDoesNotExist()
        verify(exactly = 0) { voice.manager.speak(any()) }
    }
}

package com.maggie.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.AgUiEvent
import com.maggie.app.data.model.ChatMessage
import com.maggie.app.data.model.PendingApproval
import com.maggie.app.screentest.FakeChat
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import io.mockk.coVerify
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * « Maggie demande votre accord » with the keyboard open (MAG-672): the keyboard leaves the
 * conversation a few lines of height, and the card — last of the thread — used to be cut under
 * « Détails », Refuser and Autoriser out of sight. The card is now the last thing of the
 * thread itself: the list follows it to its last pixel, and scrolls to reach it.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ChatApprovalKeyboardScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private val history = (1..30).map { n ->
        ChatMessage(
            id = "msg-$n",
            role = if (n % 2 == 1) "user" else "assistant",
            content = "Message %02d".format(n),
            createdAt = "2026-01-05T09:%02d:00Z".format(n),
        )
    }

    private val held = PendingApproval(
        id = "ap-1",
        toolName = "delete_event",
        arguments = buildJsonObject { put("id", "01KNZ8J6AGQ0K5D3WXYZ123456") },
        summary = "Supprimer l'événement « [TEST-RECETTE] Dentiste » — le 27 octobre à 19:08",
    )

    /** The panel in the height the keyboard leaves it: header, tabs and input take most of it. */
    private fun showUnderKeyboard(chat: FakeChat) {
        compose.setContent {
            Box(modifier = Modifier.height(KEYBOARD_OPEN_HEIGHT)) { ChatPanel(viewModel = chat.viewModel) }
        }
        compose.waitForIdle()
    }

    @Test
    fun `Refuser and Autoriser are in view without a gesture while the keyboard is open`() {
        showUnderKeyboard(FakeChat(history = history, pending = listOf(held)))

        compose.onNodeWithTag(UiTags.approvalAllow("ap-1")).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.approvalDeny("ap-1")).assertIsDisplayed()

        val input = compose.onNodeWithTag(UiTags.CHAT_INPUT).fetchSemanticsNode().boundsInRoot
        val allow = compose.onNodeWithTag(UiTags.approvalAllow("ap-1")).fetchSemanticsNode().boundsInRoot
        assertTrue("Autoriser is hidden under the input field", allow.bottom <= input.top)
    }

    @Test
    fun `Autoriser answers the held action from there`() {
        val chat = FakeChat(history = history, pending = listOf(held))
        showUnderKeyboard(chat)

        compose.onNodeWithTag(UiTags.approvalAllow("ap-1")).performClick()
        compose.waitForIdle()

        coVerify { chat.approvals.approve("ap-1") }
    }

    @Test
    fun `the buttons stay in view while the answer above the card keeps growing`() {
        val reply = MutableSharedFlow<AgUiEvent>(extraBufferCapacity = 256)
        val chat = FakeChat(history = history, pending = listOf(held), reply = reply)
        compose.setContent {
            Box(modifier = Modifier.height(ROOMY_HEIGHT)) { ChatPanel(viewModel = chat.viewModel) }
        }
        compose.waitForIdle()
        compose.onNodeWithTag(UiTags.CHAT_INPUT).performTextInput("Supprime")
        compose.onNodeWithTag(UiTags.CHAT_SEND).performClick()
        compose.waitForIdle()

        reply.tryEmit(AgUiEvent.TextMessageStart(messageId = "answer"))
        reply.tryEmit(AgUiEvent.TextMessageContent(messageId = "answer", delta = "Ligne 1"))
        compose.waitForIdle()
        for (n in 2..6) {
            reply.tryEmit(AgUiEvent.TextMessageContent(messageId = "answer", delta = "\nLigne $n"))
            compose.waitForIdle()
        }

        compose.onNodeWithTag(UiTags.approvalAllow("ap-1")).assertIsDisplayed()
        val input = compose.onNodeWithTag(UiTags.CHAT_INPUT).fetchSemanticsNode().boundsInRoot
        val allow = compose.onNodeWithTag(UiTags.approvalAllow("ap-1")).fetchSemanticsNode().boundsInRoot
        assertTrue("Autoriser is hidden under the input field", allow.bottom <= input.top)
    }

    @Test
    fun `the Mind tab leads with the held action, its buttons in view`() {
        val chat = FakeChat(history = history, pending = listOf(held))
        compose.setContent {
            Box(modifier = Modifier.height(MIND_KEYBOARD_OPEN_HEIGHT)) { ChatPanel(viewModel = chat.viewModel) }
        }
        compose.onNodeWithTag(UiTags.MIND_TAB).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(UiTags.approvalAllow("ap-1")).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.approvalDeny("ap-1")).assertIsDisplayed()
    }

    private companion object {
        val ROOMY_HEIGHT = 560.dp
        val MIND_KEYBOARD_OPEN_HEIGHT = 380.dp
        val KEYBOARD_OPEN_HEIGHT = 240.dp
    }
}

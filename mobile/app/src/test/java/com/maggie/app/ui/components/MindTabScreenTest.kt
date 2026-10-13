package com.maggie.app.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.AgUiEvent
import com.maggie.app.data.model.PendingApproval
import com.maggie.app.screentest.FakeChat
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.screens.chat.ChatScreen
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * « Chat | Mind » at the top of the chatbox (MAG-403): the tabs, the badge of held actions, and
 * what the Mind tab shows — Maggie's activity in plain words and the summary of what waits for
 * an answer, never a tool name or an id. The panel stands for the chatbox of a big window; the
 * Chat screen carries the same tabs.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w1280dp-h800dp-xhdpi")
class MindTabScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private val ulid = "01KNZ8J6AGQ0K5D3WXYZ123456"

    private val held = PendingApproval(
        id = "ap-1",
        toolName = "delete_event",
        arguments = buildJsonObject { put("id", ulid) },
        summary = "Supprimer l'événement « Test validation » — le 8 octobre à 10:00",
        expiresAt = "2099-01-01T00:00:00Z",
    )

    private fun hold(): FakeChat = FakeChat(pending = listOf(held))

    private fun badgeCount() = compose.onNode(
        hasText("1").and(hasAnyAncestor(hasTestTag(UiTags.MIND_BADGE))),
        useUnmergedTree = true,
    )

    private fun openMind() {
        compose.onNodeWithTag(UiTags.MIND_TAB).performClick()
        compose.waitForIdle()
    }

    @Test
    fun `the chatbox has a Chat tab and a Mind tab, on the conversation first`() {
        val chat = FakeChat()
        compose.setContent { ChatPanel(viewModel = chat.viewModel) }

        compose.onNodeWithTag(UiTags.CHAT_TAB).assertIsDisplayed().assertIsSelected()
        compose.onNodeWithTag(UiTags.MIND_TAB).assertIsDisplayed()
        compose.onNodeWithText("Qu'est-ce qu'il me reste à acheter ?").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.MIND_CONTENT).assertDoesNotExist()
    }

    @Test
    fun `no held action, no badge`() {
        val chat = FakeChat()
        compose.setContent { ChatPanel(viewModel = chat.viewModel) }
        compose.waitForIdle()

        compose.onAllNodesWithTag(UiTags.MIND_BADGE, useUnmergedTree = true).let {
            assertEquals(0, it.fetchSemanticsNodes().size)
        }
    }

    @Test
    fun `a held action puts its count on the Mind tab`() {
        val chat = hold()
        compose.setContent { ChatPanel(viewModel = chat.viewModel) }
        compose.waitForIdle()

        badgeCount().assertTextEquals("1")
        compose.onNode(hasContentDescription("Mind, 1 en attente de validation")).assertIsDisplayed()
    }

    @Test
    fun `the Mind tab shows the readable summary and neither the tool nor the id`() {
        val chat = hold()
        compose.setContent { ChatPanel(viewModel = chat.viewModel) }
        compose.waitForIdle()

        openMind()

        compose.onNodeWithTag(UiTags.MIND_TAB).assertIsSelected()
        compose.onNodeWithText("Supprimer l'événement « Test validation » — le 8 octobre à 10:00").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.approvalAllow("ap-1")).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.approvalDeny("ap-1")).assertIsDisplayed()
        compose.onNodeWithText("delete_event", substring = true).assertDoesNotExist()
        compose.onNodeWithText(ulid, substring = true).assertDoesNotExist()
        compose.onNodeWithText("Maggie est au repos").assertIsDisplayed()
    }

    @Test
    fun `refusing from the Mind tab clears the badge and the card`() {
        val chat = hold()
        compose.setContent { ChatPanel(viewModel = chat.viewModel) }
        compose.waitForIdle()
        openMind()

        compose.onNodeWithTag(UiTags.approvalDeny("ap-1")).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(UiTags.approvalCard("ap-1")).assertDoesNotExist()
        assertEquals(0, compose.onAllNodesWithTag(UiTags.MIND_BADGE, useUnmergedTree = true).fetchSemanticsNodes().size)
        compose.onNodeWithText("Aucune validation en attente").assertIsDisplayed()
    }

    @Test
    fun `the activity names a held tool in plain words and says it waits for validation`() {
        val chat = FakeChat(
            reply = flowOf(
                AgUiEvent.RunStarted("run-1"),
                AgUiEvent.ToolCallStart("call-1", "delete_event"),
                AgUiEvent.ToolCallEnd("call-1", "delete_event"),
                AgUiEvent.ToolResult("call-1", "delete_event", "pending_approval"),
                AgUiEvent.TextMessageStart("m-1"),
                AgUiEvent.TextMessageContent("m-1", "J'attends ton accord."),
                AgUiEvent.TextMessageEnd("m-1"),
                AgUiEvent.RunFinished("run-1"),
            ),
        )
        compose.setContent { ChatPanel(viewModel = chat.viewModel) }

        compose.onNodeWithTag(UiTags.CHAT_INPUT).performTextInput("Supprime mon événement")
        compose.onNodeWithTag(UiTags.CHAT_SEND).performClick()
        compose.waitForIdle()
        openMind()

        compose.onNodeWithTag(UiTags.MIND_TOOL_CALL).assertIsDisplayed()
        compose.onNodeWithText("Suppression d'un événement").assertIsDisplayed()
        compose.onNodeWithText("En attente de validation").assertIsDisplayed()
        compose.onNodeWithText("delete_event", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Maggie est au repos").assertIsDisplayed()
    }

    @Test
    fun `the Chat screen carries the same tabs`() {
        val chat = hold()
        compose.setContent { ChatScreen(viewModel = chat.viewModel) }
        compose.waitForIdle()

        compose.onNodeWithTag(UiTags.CHAT_TAB).assertIsDisplayed()
        badgeCount().assertTextEquals("1")

        openMind()
        compose.onNodeWithText("Supprimer l'événement « Test validation » — le 8 octobre à 10:00").assertIsDisplayed()
    }
}

package com.maggie.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.model.AgUiEvent
import com.maggie.app.data.model.ChatMessage
import com.maggie.app.data.repository.UserPreferenceRepository
import com.maggie.app.screentest.FakeChat
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import com.maggie.app.voice.VoiceManager
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Where the conversation opens, and where it stays while Maggie answers (MAG-348).
 *
 * The three surfaces — voice sheet, text sheet, panel — each start from a list state
 * of their own, at the oldest message. The scroll command that carries the list to
 * the latest one is sent once, by the ViewModel, and a surface opened after it was
 * consumed never heard it: the owner opened the mic on his phone and read old
 * messages. These tests close and reopen each surface on a conversation of 30
 * messages and ask for the last one.
 *
 * The second half is the other symptom of the same logic: an answer that grows below
 * the fold. The list follows it, whatever the height it has — a bubble taller than the
 * window included — until the user pulls the list up on purpose.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w1280dp-h800dp-xhdpi")
class ChatStaysAtBottomScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private val thirtyMessages = (1..30).map { n ->
        ChatMessage(
            id = "msg-$n",
            role = if (n % 2 == 1) "user" else "assistant",
            content = "Message %02d".format(n),
            createdAt = "2026-01-05T09:%02d:00Z".format(n),
        )
    }

    private fun lastVisibleFirstGone() {
        compose.onNodeWithText("Message 30").assertIsDisplayed()
        compose.onNodeWithText("Message 01").assertDoesNotExist()
    }

    /** Shows [surface], takes it away, and shows it again: the second opening is the one that was wrong. */
    private fun openCloseOpen(surface: @Composable () -> Unit) {
        var shown by mutableStateOf(true)
        compose.setContent { if (shown) surface() }
        compose.waitForIdle()
        lastVisibleFirstGone()

        shown = false
        compose.waitForIdle()
        shown = true
        compose.waitForIdle()
    }

    @Test
    fun `the panel reopens on the last message`() {
        val chat = FakeChat(history = thirtyMessages)

        openCloseOpen { ChatPanel(viewModel = chat.viewModel) }

        lastVisibleFirstGone()
    }

    @Test
    fun `the text sheet reopens on the last message`() {
        val chat = FakeChat(history = thirtyMessages)

        openCloseOpen { TextSheet(chat) }

        lastVisibleFirstGone()
    }

    @Test
    fun `the voice sheet reopens on the last message`() {
        val chat = FakeChat(history = thirtyMessages)

        openCloseOpen { VoiceSheet(chat) }

        lastVisibleFirstGone()
    }

    /** The ViewModel was loaded, and its command consumed, before this surface existed at all. */
    @Test
    fun `a sheet opened on a conversation already loaded starts on the last message`() {
        val chat = FakeChat(history = thirtyMessages)
        var panel by mutableStateOf(true)
        var sheet by mutableStateOf(false)
        compose.setContent {
            if (panel) ChatPanel(viewModel = chat.viewModel)
            if (sheet) TextSheet(chat)
        }
        compose.waitForIdle()
        panel = false
        sheet = true
        compose.waitForIdle()

        lastVisibleFirstGone()
    }

    @Test
    fun `an answer taller than the window is followed to its last line`() {
        val reply = MutableSharedFlow<AgUiEvent>(extraBufferCapacity = 256)
        val chat = FakeChat(history = thirtyMessages, reply = reply)
        compose.setContent { ChatPanel(viewModel = chat.viewModel) }
        compose.waitForIdle()
        ask(chat, "Raconte")

        reply.tryEmit(AgUiEvent.TextMessageStart(messageId = "answer"))
        for (n in 1..80) reply.tryEmit(AgUiEvent.TextMessageContent(messageId = "answer", delta = "Ligne $n\n"))
        compose.waitForIdle()

        assertTrue("the end of the answer is below the fold", endOfAnswerIsInView())
    }

    @Test
    fun `the answer stays in view when the keyboard or a card takes height from the list`() {
        val reply = MutableSharedFlow<AgUiEvent>(extraBufferCapacity = 256)
        val chat = FakeChat(history = thirtyMessages, reply = reply)
        var height by mutableStateOf(700.dp)
        compose.setContent {
            Box(modifier = Modifier.height(height)) { ChatPanel(viewModel = chat.viewModel) }
        }
        compose.waitForIdle()
        ask(chat, "Raconte")
        reply.tryEmit(AgUiEvent.TextMessageStart(messageId = "answer"))
        for (n in 1..80) reply.tryEmit(AgUiEvent.TextMessageContent(messageId = "answer", delta = "Ligne $n\n"))
        compose.waitForIdle()

        height = 350.dp
        compose.waitForIdle()

        assertTrue("the end of the answer slipped below the fold", endOfAnswerIsInView())
    }

    @Test
    fun `pulling the list up stops the following and offers the way back`() {
        val reply = MutableSharedFlow<AgUiEvent>(extraBufferCapacity = 256)
        val chat = FakeChat(history = thirtyMessages, reply = reply)
        compose.setContent { ChatPanel(viewModel = chat.viewModel) }
        compose.waitForIdle()
        ask(chat, "Raconte")
        reply.tryEmit(AgUiEvent.TextMessageStart(messageId = "answer"))
        for (n in 1..80) reply.tryEmit(AgUiEvent.TextMessageContent(messageId = "answer", delta = "Ligne $n\n"))
        compose.waitForIdle()

        compose.onNodeWithTag(UiTags.CHAT_MESSAGES).performTouchInput { swipeDown() }
        compose.waitForIdle()
        for (n in 81..120) reply.tryEmit(AgUiEvent.TextMessageContent(messageId = "answer", delta = "Ligne $n\n"))
        compose.waitForIdle()

        assertTrue("the list was dragged back down to the answer", !endOfAnswerIsInView())
        compose.onNodeWithTag(UiTags.CHAT_JUMP_TO_LATEST).assertIsDisplayed()

        compose.onNodeWithTag(UiTags.CHAT_JUMP_TO_LATEST).performClick()
        compose.waitForIdle()

        assertTrue("the button did not bring the answer back", endOfAnswerIsInView())
        compose.onNodeWithTag(UiTags.CHAT_JUMP_TO_LATEST).assertDoesNotExist()
    }

    private fun ask(chat: FakeChat, text: String) {
        compose.onNodeWithTag(UiTags.CHAT_INPUT).performTextInput(text)
        compose.onNodeWithTag(UiTags.CHAT_SEND).performClick()
        compose.waitForIdle()
        assertTrue(chat.sent.contains(text))
    }

    private fun endOfAnswerIsInView(): Boolean {
        val list = compose.onNodeWithTag(UiTags.CHAT_MESSAGES).bottom()
        // Scrolled out of the list, the bubble is not composed at all.
        val answer = compose.onAllNodesWithText("Ligne", substring = true).fetchSemanticsNodes().singleOrNull()
            ?: return false
        return answer.boundsInRoot.bottom <= list
    }

    private fun SemanticsNodeInteraction.bottom(): Float = fetchSemanticsNode().boundsInRoot.bottom

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun TextSheet(chat: FakeChat) {
        ChatSheet(
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            viewModel = chat.viewModel,
            onDismiss = {},
        )
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun VoiceSheet(chat: FakeChat) {
        ChatSheet(
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            viewModel = chat.viewModel,
            onDismiss = {},
            voiceManager = voice,
        )
    }

    private val voice: VoiceManager by lazy {
        VoiceManager(
            mockk(relaxed = true),
            mockk<MaggieApiService>(relaxed = true),
            mockk<UserPreferenceRepository>(relaxed = true),
        )
    }
}

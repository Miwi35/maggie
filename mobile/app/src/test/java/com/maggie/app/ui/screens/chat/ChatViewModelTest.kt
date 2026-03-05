package com.maggie.app.ui.screens.chat

import android.util.Log
import app.cash.turbine.test
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.model.AgUiEvent
import com.maggie.app.data.model.ChatMessage
import com.maggie.app.data.repository.ChatPreferencesRepository
import com.maggie.app.data.repository.ChatRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var repository: ChatRepository
    private lateinit var mercureService: MercureService
    private lateinit var chatPrefsRepository: ChatPreferencesRepository
    private lateinit var viewModel: ChatViewModel

    private val sampleMessages = listOf(
        ChatMessage(id = "msg-1", role = "user", content = "Hello", createdAt = "2026-02-15T10:00:00Z"),
        ChatMessage(id = "msg-2", role = "assistant", content = "Hi!", createdAt = "2026-02-15T10:00:05Z"),
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.d(any(), any<String>()) } returns 0
        repository = mockk()
        mercureService = mockk()
        chatPrefsRepository = mockk()
        every { mercureService.subscribe(any()) } returns emptyFlow()
        coEvery { repository.loadRecentMessages(any()) } returns sampleMessages
        coEvery { chatPrefsRepository.getLastReadMessageId() } returns null
        coEvery { chatPrefsRepository.saveLastReadMessageId(any()) } returns Unit
    }

    private fun createViewModel(): ChatViewModel {
        return ChatViewModel(repository, mercureService, chatPrefsRepository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial load populates messages with date separators`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(2, state.messages.size)
        // Display items should include at least a date separator + 2 messages
        assertTrue(state.displayItems.any { it is ChatListItem.DateSeparator })
        assertEquals(2, state.displayItems.count { it is ChatListItem.MessageItem })
        assertFalse(state.isLoading)
    }

    @Test
    fun `sendMessage via stream accumulates text and persists on end`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        val streamEvents = flowOf(
            AgUiEvent.RunStarted(runId = "run-1"),
            AgUiEvent.TextMessageStart(messageId = "resp-1"),
            AgUiEvent.TextMessageContent(messageId = "resp-1", delta = "Sal"),
            AgUiEvent.TextMessageContent(messageId = "resp-1", delta = "ut!"),
            AgUiEvent.TextMessageEnd(messageId = "resp-1"),
            AgUiEvent.RunFinished(runId = "run-1"),
        )
        every { repository.sendMessageStream("Bonjour") } returns streamEvents
        coEvery { repository.persistMessage(any()) } returns Unit

        viewModel.sendMessage("Bonjour")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        // 2 initial + 1 optimistic user + 1 persisted assistant
        assertEquals(4, state.messages.size)
        assertEquals("Salut!", state.messages.last().content)
        assertEquals("", state.streamingText)
        assertNull(state.streamingMessageId)
        coVerify { repository.persistMessage(any()) }
    }

    @Test
    fun `sendMessage stream fallback on error`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        // Stream throws, fallback to non-streaming
        every { repository.sendMessageStream("Hello") } returns flow {
            throw RuntimeException("Stream failed")
        }
        val userMsg = ChatMessage(id = "u-1", role = "user", content = "Hello", createdAt = "2026-02-15T11:00:00Z")
        val assistantMsg = ChatMessage(id = "a-1", role = "assistant", content = "Hi!", createdAt = "2026-02-15T11:00:01Z")
        coEvery { repository.sendMessage("Hello") } returns listOf(userMsg, assistantMsg)

        viewModel.sendMessage("Hello")
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isLoading)
        // Should have used fallback: 2 initial (minus optimistic) + 2 from fallback
        coVerify { repository.sendMessage("Hello") }
    }

    @Test
    fun `sendMessage failure clears loading`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        // Both stream and fallback fail
        every { repository.sendMessageStream("Hello") } returns flow {
            throw RuntimeException("Stream error")
        }
        coEvery { repository.sendMessage("Hello") } throws RuntimeException("Network error")

        viewModel.sendMessage("Hello")
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test
    fun `sendMessage with blank text does nothing`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        val messagesBefore = viewModel.uiState.value.messages.size
        viewModel.sendMessage("   ")
        advanceUntilIdle()

        assertEquals(messagesBefore, viewModel.uiState.value.messages.size)
        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test
    fun `loadOlderMessages prepends history`() = runTest {
        // Return PAGE_SIZE messages so hasMoreHistory = true
        val fullPage = (1..20).map {
            ChatMessage(id = "msg-$it", role = "user", content = "Msg $it", createdAt = "2026-02-15T10:${it.toString().padStart(2, '0')}:00Z")
        }
        coEvery { repository.loadRecentMessages(any()) } returns fullPage
        viewModel = createViewModel()
        advanceUntilIdle()

        val olderMessages = listOf(
            ChatMessage(id = "old-1", role = "user", content = "Old msg", createdAt = "2026-02-14T09:00:00Z"),
        )
        coEvery { repository.loadOlderMessages("msg-1", any()) } returns olderMessages

        viewModel.loadOlderMessages()
        advanceUntilIdle()

        assertEquals(21, viewModel.uiState.value.messages.size)
        assertEquals("old-1", viewModel.uiState.value.messages.first().id)
    }

    @Test
    fun `search debounce triggers after query change`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        val searchResults = listOf(
            ChatMessage(id = "s-1", role = "assistant", content = "Found it", createdAt = "2026-02-15T10:00:00Z"),
        )
        coEvery { repository.searchMessages("test", any()) } returns searchResults

        viewModel.openSearch()
        viewModel.onSearchQueryChanged("test")
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isSearchMode)
        assertEquals(1, viewModel.uiState.value.searchResults.size)
    }

    @Test
    fun `navigateToMessage highlights target message`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.navigateToMessage("msg-2")
        // Advance enough for the coroutine to run but not the 2s highlight-clear delay
        advanceTimeBy(100)

        assertEquals("msg-2", viewModel.uiState.value.highlightedMessageId)
        val highlighted = viewModel.uiState.value.displayItems
            .filterIsInstance<ChatListItem.MessageItem>()
            .find { it.message.id == "msg-2" }
        assertTrue(highlighted?.isHighlighted == true)
    }

    @Test
    fun `onScrolledToBottom clears unread`() = runTest {
        coEvery { chatPrefsRepository.getLastReadMessageId() } returns "msg-1"
        viewModel = createViewModel()
        advanceUntilIdle()

        // Should have unread divider since msg-2 is after msg-1
        assertEquals("msg-2", viewModel.uiState.value.unreadFromId)

        viewModel.onScrolledToBottom()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.unreadFromId)
        coVerify { chatPrefsRepository.saveLastReadMessageId("msg-2") }
    }

    @Test
    fun `onMessageTapped toggles tapped state`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onMessageTapped("msg-1")
        assertEquals("msg-1", viewModel.uiState.value.tappedMessageId)

        viewModel.onMessageTapped("msg-1")
        assertNull(viewModel.uiState.value.tappedMessageId)
    }

    @Test
    fun `unread divider appears when lastReadId is set`() = runTest {
        coEvery { chatPrefsRepository.getLastReadMessageId() } returns "msg-1"
        viewModel = createViewModel()
        advanceUntilIdle()

        val hasUnreadDivider = viewModel.uiState.value.displayItems.any { it is ChatListItem.UnreadDivider }
        assertTrue(hasUnreadDivider)
    }

    @Test
    fun `streaming text shows StreamingMessage display item`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        // Simulate partial streaming
        val streamEvents = flow {
            emit(AgUiEvent.RunStarted(runId = "run-1"))
            emit(AgUiEvent.TextMessageStart(messageId = "resp-1"))
            emit(AgUiEvent.TextMessageContent(messageId = "resp-1", delta = "Partial"))
            // Don't send end — simulate mid-stream state
        }
        every { repository.sendMessageStream("Test") } returns streamEvents
        coEvery { repository.persistMessage(any()) } returns Unit

        viewModel.sendMessage("Test")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("Partial", state.streamingText)
        assertTrue(state.displayItems.any { it is ChatListItem.StreamingMessage })
    }

    @Test
    fun `context update emitted on SharedFlow`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        val contextEvent = AgUiEvent.ContextUpdate(
            id = "ctx-1", label = "Courses", status = "active", action = "created",
        )
        val streamEvents = flowOf(
            AgUiEvent.RunStarted(runId = "run-1"),
            contextEvent,
            AgUiEvent.TextMessageStart(messageId = "resp-1"),
            AgUiEvent.TextMessageContent(messageId = "resp-1", delta = "OK"),
            AgUiEvent.TextMessageEnd(messageId = "resp-1"),
            AgUiEvent.RunFinished(runId = "run-1"),
        )
        every { repository.sendMessageStream("Test") } returns streamEvents
        coEvery { repository.persistMessage(any()) } returns Unit

        viewModel.contextUpdates.test {
            viewModel.sendMessage("Test")
            advanceUntilIdle()

            val update = awaitItem()
            assertEquals("ctx-1", update.id)
            assertEquals("Courses", update.label)
            assertEquals("active", update.status)
            cancelAndIgnoreRemainingEvents()
        }
    }
}

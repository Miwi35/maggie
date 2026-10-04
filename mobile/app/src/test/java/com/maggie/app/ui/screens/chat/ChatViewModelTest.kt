package com.maggie.app.ui.screens.chat

import android.util.Log
import app.cash.turbine.test
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureEvent
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
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
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
    private lateinit var authRepository: AuthRepository
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
        authRepository = mockk()
        coEvery { authRepository.getUserId() } returns "user-1"
        every { mercureService.subscribe(any()) } returns emptyFlow()
        coEvery { repository.loadRecentMessages(any()) } returns sampleMessages
        coEvery { chatPrefsRepository.getLastReadMessageId() } returns null
        coEvery { chatPrefsRepository.saveLastReadMessageId(any()) } returns Unit
    }

    private fun createViewModel(): ChatViewModel {
        return ChatViewModel(repository, mercureService, chatPrefsRepository, authRepository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `subscribes to the chat topic of the signed-in user, not a placeholder`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        verify { mercureService.subscribe("/chat/user-1") }
    }

    @Test
    fun `does not subscribe to the chat topic when nobody is signed in`() = runTest {
        coEvery { authRepository.getUserId() } returns null

        viewModel = createViewModel()
        advanceUntilIdle()

        verify(exactly = 0) { mercureService.subscribe(any()) }
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
    fun `the screen context travels inside the message, never inside the bubble`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        val block = "[Contexte de l'écran]\nPage : https://boutique.example/cafe"
        every { repository.sendMessageStream(any()) } returns flowOf(AgUiEvent.RunFinished(runId = "run-1"))

        viewModel.sendMessage("ajoute ça à mon agenda", block)
        advanceUntilIdle()

        verify { repository.sendMessageStream("$block\n\najoute ça à mon agenda") }
        assertEquals("ajoute ça à mon agenda", viewModel.uiState.value.messages.last().content)
    }

    @Test
    fun `no screen context means no prefix, not an empty one`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        every { repository.sendMessageStream(any()) } returns flowOf(AgUiEvent.RunFinished(runId = "run-1"))

        viewModel.sendMessage("bonjour", screenContext = "   ")
        advanceUntilIdle()

        verify { repository.sendMessageStream("bonjour") }
    }

    @Test
    fun `the fallback sends the context too, not the bare question`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        val block = "[Contexte de l'écran]\nApplication : Boutique (com.example.shop)"
        every { repository.sendMessageStream(any()) } returns flow { throw RuntimeException("Stream failed") }
        coEvery { repository.sendMessage(any()) } returns emptyList()

        viewModel.sendMessage("c'est quoi ce produit ?", block)
        advanceUntilIdle()

        coVerify { repository.sendMessage("$block\n\nc'est quoi ce produit ?") }
    }

    @Test
    fun `history that comes back carrying a screen context shows only what was said`() = runTest {
        // What the agent stores is the string it was POSTed, block included.
        val stored = "[Contexte de l'écran]\nApplication : Boutique (com.example.shop)\n\nc'est quoi ce produit ?"
        coEvery { repository.loadRecentMessages(any()) } returns listOf(
            ChatMessage(id = "msg-1", role = "user", content = stored, createdAt = "2026-02-15T10:00:00Z"),
            ChatMessage(id = "msg-2", role = "assistant", content = "Du café.", createdAt = "2026-02-15T10:00:05Z"),
        )

        viewModel = createViewModel()
        advanceUntilIdle()

        val messages = viewModel.uiState.value.messages
        assertEquals("c'est quoi ce produit ?", messages.first().content)
        assertEquals("Du café.", messages.last().content)
    }

    @Test
    fun `the fallback's copy of the user message is shown as what was said`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        val block = "[Contexte de l'écran]\nApplication : Boutique (com.example.shop)"
        every { repository.sendMessageStream(any()) } returns flow { throw RuntimeException("Stream failed") }
        coEvery { repository.sendMessage(any()) } returns listOf(
            ChatMessage(
                id = "u-1",
                role = "user",
                content = "$block\n\nc'est quoi ce produit ?",
                createdAt = "2026-02-15T11:00:00Z",
            ),
            ChatMessage(id = "a-1", role = "assistant", content = "Du café.", createdAt = "2026-02-15T11:00:01Z"),
        )

        viewModel.sendMessage("c'est quoi ce produit ?", block)
        advanceUntilIdle()

        val messages = viewModel.uiState.value.messages
        assertEquals("c'est quoi ce produit ?", messages[messages.size - 2].content)
        assertFalse(messages.any { it.content.contains("[Contexte de l'écran]") })
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

    // --- MAG-227: only the answer to a request made in this session is read aloud ---

    @Test
    fun `opening on a history that ends with Maggie reads nothing aloud`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals("assistant", viewModel.uiState.value.messages.last().role)
        assertNull(viewModel.uiState.value.replyToSpeak)
    }

    @Test
    fun `history still loading is not a request awaiting an answer`() = runTest {
        val history = kotlinx.coroutines.CompletableDeferred<List<ChatMessage>>()
        coEvery { repository.loadRecentMessages(any()) } coAnswers { history.await() }

        viewModel = createViewModel()
        // Mid-load: isLoading is up, which is what the old overlay took for a request.
        runCurrent()
        assertTrue(viewModel.uiState.value.isLoading)
        assertNull(viewModel.uiState.value.replyToSpeak)

        history.complete(sampleMessages)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isLoading)
        assertNull(viewModel.uiState.value.replyToSpeak)
    }

    @Test
    fun `the answer to a request is offered to be read once, then consumed`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()
        every { repository.sendMessageStream("Bonjour") } returns streamedAnswer("resp-1", "Salut!")
        coEvery { repository.persistMessage(any()) } returns Unit

        viewModel.sendMessage("Bonjour")
        advanceUntilIdle()

        val reply = viewModel.uiState.value.replyToSpeak
        assertEquals("resp-1", reply?.id)
        assertEquals("Salut!", reply?.content)

        viewModel.onReplySpoken()
        assertNull(viewModel.uiState.value.replyToSpeak)
    }

    @Test
    fun `nothing is offered while the answer is still streaming`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()
        every { repository.sendMessageStream("Bonjour") } returns flow {
            emit(AgUiEvent.RunStarted(runId = "run-1"))
            emit(AgUiEvent.TextMessageStart(messageId = "resp-1"))
            emit(AgUiEvent.TextMessageContent(messageId = "resp-1", delta = "Sal"))
            kotlinx.coroutines.awaitCancellation()
        }

        viewModel.sendMessage("Bonjour")
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.replyToSpeak)
    }

    @Test
    fun `the non-streaming fallback answer is offered too`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()
        every { repository.sendMessageStream("Hello") } returns flow { throw RuntimeException("Stream failed") }
        val userMsg = ChatMessage(id = "u-1", role = "user", content = "Hello", createdAt = "2026-02-15T11:00:00Z")
        val assistantMsg = ChatMessage(id = "a-1", role = "assistant", content = "Hi!", createdAt = "2026-02-15T11:00:01Z")
        coEvery { repository.sendMessage("Hello") } returns listOf(userMsg, assistantMsg)

        viewModel.sendMessage("Hello")
        advanceUntilIdle()

        assertEquals("a-1", viewModel.uiState.value.replyToSpeak?.id)
    }

    @Test
    fun `a failed request offers nothing`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()
        every { repository.sendMessageStream("Hello") } returns flow { throw RuntimeException("Stream error") }
        coEvery { repository.sendMessage("Hello") } throws RuntimeException("Network error")

        viewModel.sendMessage("Hello")
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.replyToSpeak)
    }

    @Test
    fun `closing before the answer lands means it is never offered, even on reopening`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        every { repository.sendMessageStream("Bonjour") } returns flow {
            emit(AgUiEvent.RunStarted(runId = "run-1"))
            gate.await()
            emit(AgUiEvent.TextMessageStart(messageId = "resp-1"))
            emit(AgUiEvent.TextMessageContent(messageId = "resp-1", delta = "Salut!"))
            emit(AgUiEvent.TextMessageEnd(messageId = "resp-1"))
            emit(AgUiEvent.RunFinished(runId = "run-1"))
        }
        coEvery { repository.persistMessage(any()) } returns Unit

        viewModel.sendMessage("Bonjour")
        advanceUntilIdle()
        viewModel.dropPendingReply()
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals("Salut!", viewModel.uiState.value.messages.last().content)
        assertNull(viewModel.uiState.value.replyToSpeak)
    }

    @Test
    fun `a proactive message that nobody asked for is not offered`() = runTest {
        val mercure = MutableSharedFlow<MercureEvent>(extraBufferCapacity = 4)
        every { mercureService.subscribe(any()) } returns mercure

        viewModel = createViewModel()
        advanceUntilIdle()
        mercure.emit(
            MercureEvent(data = """{"id":"p-1","role":"assistant","content":"Rappel : dentiste demain","createdAt":"2026-02-15T12:00:00Z"}"""),
        )
        advanceUntilIdle()

        assertEquals("p-1", viewModel.uiState.value.messages.last().id)
        assertNull(viewModel.uiState.value.replyToSpeak)
    }

    private fun streamedAnswer(id: String, text: String) = flowOf(
        AgUiEvent.RunStarted(runId = "run-1"),
        AgUiEvent.TextMessageStart(messageId = id),
        AgUiEvent.TextMessageContent(messageId = id, delta = text),
        AgUiEvent.TextMessageEnd(messageId = id),
        AgUiEvent.RunFinished(runId = "run-1"),
    )

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

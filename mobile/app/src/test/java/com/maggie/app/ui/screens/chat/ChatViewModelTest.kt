package com.maggie.app.ui.screens.chat

import android.util.Log
import app.cash.turbine.test
import com.maggie.app.data.api.ApprovalDecisionException
import com.maggie.app.data.api.ChatImage
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureEvent
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.model.AgUiEvent
import com.maggie.app.data.model.ChatMessage
import com.maggie.app.data.model.PendingApproval
import com.maggie.app.data.repository.ApprovalRepository
import com.maggie.app.data.repository.ChatPreferencesRepository
import com.maggie.app.data.repository.ChatRepository
import com.maggie.app.ui.screens.contexts.ThreadEvent
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.yield
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
    private lateinit var approvalRepository: ApprovalRepository
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
        approvalRepository = mockk()
        coEvery { approvalRepository.getPending() } returns Result.success(emptyList())
        every { approvalRepository.observe() } returns emptyFlow()
        coEvery { authRepository.getUserId() } returns "user-1"
        every { mercureService.subscribe(any()) } returns emptyFlow()
        coEvery { repository.loadRecentMessages(any()) } returns sampleMessages
        coEvery { chatPrefsRepository.getLastReadMessageId() } returns null
        coEvery { chatPrefsRepository.saveLastReadMessageId(any()) } returns Unit
    }

    private fun createViewModel(): ChatViewModel {
        return ChatViewModel(repository, mercureService, chatPrefsRepository, authRepository, approvalRepository)
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
        every { repository.sendMessageStream("Bonjour", any(), any()) } returns streamEvents
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
        every { repository.sendMessageStream("Hello", any(), any()) } returns flow {
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
        every { repository.sendMessageStream("Hello", any(), any()) } returns flow {
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
    fun `the screen context travels in its own field, never inside the message`() = runTest {
        // Glued to the message it was stored as the message, and every reader of the
        // history showed the page instead of the question — the refused recette.
        viewModel = createViewModel()
        advanceUntilIdle()

        val block = "[Contexte de l'écran]\nPage : https://boutique.example/cafe"
        every { repository.sendMessageStream(any(), any(), any()) } returns flowOf(AgUiEvent.RunFinished(runId = "run-1"))

        viewModel.sendMessage("ajoute ça à mon agenda", block)
        advanceUntilIdle()

        verify { repository.sendMessageStream("ajoute ça à mon agenda", block, any()) }
        assertEquals("ajoute ça à mon agenda", viewModel.uiState.value.messages.last().content)
    }

    @Test
    fun `no screen context means no field, not a blank one`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        every { repository.sendMessageStream(any(), any(), any()) } returns flowOf(AgUiEvent.RunFinished(runId = "run-1"))

        viewModel.sendMessage("bonjour", screenContext = "   ")
        advanceUntilIdle()

        verify { repository.sendMessageStream("bonjour", null, any()) }
    }

    @Test
    fun `the fallback sends the context too, not the bare question`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        val block = "[Contexte de l'écran]\nApplication : Boutique (com.example.shop)"
        every { repository.sendMessageStream(any(), any(), any()) } returns flow { throw RuntimeException("Stream failed") }
        coEvery { repository.sendMessage(any(), any()) } returns emptyList()

        viewModel.sendMessage("c'est quoi ce produit ?", block)
        advanceUntilIdle()

        coVerify { repository.sendMessage("c'est quoi ce produit ?", block) }
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
        every { repository.sendMessageStream(any(), any(), any()) } returns flow { throw RuntimeException("Stream failed") }
        coEvery { repository.sendMessage(any(), any()) } returns listOf(
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
            // Don't send end — the stream stays open, mid-stream state
            awaitCancellation()
        }
        every { repository.sendMessageStream("Test", any(), any()) } returns streamEvents
        coEvery { repository.persistMessage(any()) } returns Unit

        viewModel.sendMessage("Test")
        runCurrent()

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
        every { repository.sendMessageStream("Bonjour", any(), any()) } returns streamedAnswer("resp-1", "Salut!")
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
    fun `an answer nobody was listening for is dropped when a speaker attaches`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()
        every { repository.sendMessageStream("Bonjour", any(), any()) } returns streamedAnswer("resp-1", "Salut!")
        coEvery { repository.persistMessage(any()) } returns Unit

        // Typed in the chat screen: no speaker is attached when the answer lands.
        viewModel.sendMessage("Bonjour")
        advanceUntilIdle()
        assertEquals("resp-1", viewModel.uiState.value.replyToSpeak?.id)

        // The sheet opens in voice mode: SpokenReplies drops it on entry.
        viewModel.dropPendingReply()

        assertNull(viewModel.uiState.value.replyToSpeak)
    }

    @Test
    fun `nothing is offered while the answer is still streaming`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()
        every { repository.sendMessageStream("Bonjour", any(), any()) } returns flow {
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
        every { repository.sendMessageStream("Hello", any(), any()) } returns flow { throw RuntimeException("Stream failed") }
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
        every { repository.sendMessageStream("Hello", any(), any()) } returns flow { throw RuntimeException("Stream error") }
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
        every { repository.sendMessageStream("Bonjour", any(), any()) } returns flow {
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
        coEvery { repository.handleMercureMessage(any()) } returns Unit

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
        every { repository.sendMessageStream("Test", any(), any()) } returns streamEvents
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

    // --- MAG-109: a streamed exchange is published too, so another device sees it ---

    private fun echo(id: String, role: String, content: String) = MercureEvent(
        data = """{"id":"$id","role":"$role","content":"$content","createdAt":"2026-02-15T11:00:00Z"}""",
    )

    private fun chatTopic(): MutableSharedFlow<MercureEvent> {
        val topic = MutableSharedFlow<MercureEvent>(extraBufferCapacity = 16)
        every { mercureService.subscribe("/chat/user-1") } returns topic
        return topic
    }

    @Test
    fun `the echo of the question this device sent replaces the pending one instead of doubling it`() = runTest {
        val topic = chatTopic()
        viewModel = createViewModel()
        advanceUntilIdle()
        every { repository.sendMessageStream(any(), any(), any()) } returns flow {
            emit(AgUiEvent.RunStarted(runId = "run-1"))
            topic.tryEmit(echo("u-9", "user", "Bonjour Maggie"))
            yield()
            emit(AgUiEvent.RunFinished(runId = "run-1"))
        }
        coEvery { repository.handleMercureMessage(any()) } returns Unit

        viewModel.sendMessage("Bonjour Maggie")
        advanceUntilIdle()

        val said = viewModel.uiState.value.messages.filter { it.content == "Bonjour Maggie" }
        assertEquals(listOf("u-9"), said.map { it.id })
    }

    @Test
    fun `the echo of an answer that lands before the end of its stream is not shown twice`() = runTest {
        val topic = chatTopic()
        viewModel = createViewModel()
        advanceUntilIdle()
        every { repository.sendMessageStream(any(), any(), any()) } returns flow {
            emit(AgUiEvent.RunStarted(runId = "run-1"))
            emit(AgUiEvent.TextMessageStart(messageId = "resp-1"))
            emit(AgUiEvent.TextMessageContent(messageId = "resp-1", delta = "Salut !"))
            topic.tryEmit(echo("resp-1", "assistant", "Salut !"))
            yield()
            emit(AgUiEvent.TextMessageEnd(messageId = "resp-1"))
            emit(AgUiEvent.RunFinished(runId = "run-1"))
        }
        coEvery { repository.handleMercureMessage(any()) } returns Unit
        coEvery { repository.persistMessage(any()) } returns Unit

        viewModel.sendMessage("Bonjour")
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.messages.count { it.content == "Salut !" })
    }

    @Test
    fun `a message another device sent is appended once`() = runTest {
        val topic = chatTopic()
        viewModel = createViewModel()
        advanceUntilIdle()
        coEvery { repository.handleMercureMessage(any()) } returns Unit

        topic.tryEmit(echo("u-7", "user", "Depuis le web"))
        topic.tryEmit(echo("u-7", "user", "Depuis le web"))
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.messages.count { it.content == "Depuis le web" })
    }

    // --- MAG-214: the screenshot goes with the sentence, the thumbnail stays in memory ---

    private val jpeg = byteArrayOf(-1, -40, -1, -32, 1, 2, 3)

    @Test
    fun `the screenshot is sent with the message as base64 JPEG`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()
        every { repository.sendMessageStream(any(), any(), any(), any()) } returns flowOf(AgUiEvent.RunFinished(runId = "run-1"))

        viewModel.sendMessage("c'est quoi ce produit ?", "[Contexte de l'écran]", jpeg)
        advanceUntilIdle()

        verify {
            repository.sendMessageStream(
                "c'est quoi ce produit ?",
                "[Contexte de l'écran]",
                any(),
                ChatImage("image/jpeg", java.util.Base64.getEncoder().encodeToString(jpeg)),
            )
        }
    }

    @Test
    fun `the pending bubble keeps its thumbnail, and the echo carries it over`() = runTest {
        val topic = chatTopic()
        viewModel = createViewModel()
        advanceUntilIdle()
        coEvery { repository.handleMercureMessage(any()) } returns Unit
        var pendingThumbnail: ByteArray? = null
        every { repository.sendMessageStream(any(), any(), any(), any()) } returns flow {
            val pending = viewModel.uiState.value.messages.last()
            pendingThumbnail = viewModel.uiState.value.thumbnails[pending.id]
            topic.tryEmit(
                MercureEvent(
                    data = """{"id":"u-9","role":"user","content":"c'est quoi ?","createdAt":"2026-02-15T11:00:00Z","hasImage":true}""",
                ),
            )
            yield()
            emit(AgUiEvent.RunFinished(runId = "run-1"))
        }

        viewModel.sendMessage("c'est quoi ?", null, jpeg)
        advanceUntilIdle()

        assertTrue(pendingThumbnail.contentEquals(jpeg))
        val state = viewModel.uiState.value
        val sent = state.messages.single { it.content == "c'est quoi ?" }
        assertEquals("u-9", sent.id)
        assertTrue(sent.hasImage)
        assertEquals(setOf("u-9"), state.thumbnails.keys)
        val item = state.displayItems.filterIsInstance<ChatListItem.MessageItem>().single { it.message.id == "u-9" }
        assertTrue(item.thumbnail.contentEquals(jpeg))
    }

    @Test
    fun `the fallback's stored question takes the thumbnail too`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()
        every { repository.sendMessageStream(any(), any(), any(), any()) } returns flow { throw RuntimeException("Stream failed") }
        coEvery { repository.sendMessage(any(), any(), any()) } returns listOf(
            ChatMessage(id = "u-1", role = "user", content = "c'est quoi ?", hasImage = true),
            ChatMessage(id = "a-1", role = "assistant", content = "Une cafetière."),
        )

        viewModel.sendMessage("c'est quoi ?", null, jpeg)
        advanceUntilIdle()

        assertEquals(setOf("u-1"), viewModel.uiState.value.thumbnails.keys)
    }

    @Test
    fun `a message from history that had a screenshot has no bytes to show`() = runTest {
        coEvery { repository.loadRecentMessages(any()) } returns listOf(
            ChatMessage(id = "msg-1", role = "user", content = "c'est quoi ?", hasImage = true),
        )

        viewModel = createViewModel()
        advanceUntilIdle()

        val item = viewModel.uiState.value.displayItems.filterIsInstance<ChatListItem.MessageItem>().single()
        assertTrue(item.message.hasImage)
        assertNull(item.thumbnail)
    }

    @Test
    fun `a message without a screenshot sends no image`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()
        every { repository.sendMessageStream(any(), any(), any(), any()) } returns flowOf(AgUiEvent.RunFinished(runId = "run-1"))

        viewModel.sendMessage("bonjour")
        advanceUntilIdle()

        verify { repository.sendMessageStream("bonjour", null, any(), null) }
        assertFalse(viewModel.uiState.value.messages.last().hasImage)
        assertTrue(viewModel.uiState.value.thumbnails.isEmpty())
    }

    // --- Approvals (MAG-7) ---

    private fun approval(
        id: String = "ap-1",
        status: String = PendingApproval.STATUS_PENDING,
        createdAt: String = "2026-10-06T10:00:00Z",
    ) = PendingApproval(
        id = id,
        toolName = "delete_event",
        arguments = buildJsonObject { put("id", "evt-1") },
        status = status,
        createdAt = createdAt,
        expiresAt = "2026-10-07T10:00:00Z",
    )

    @Test
    fun `pending approvals are loaded when the chat opens`() = runTest {
        coEvery { approvalRepository.getPending() } returns Result.success(
            listOf(approval("ap-1"), approval("ap-2", createdAt = "2026-10-06T11:00:00Z")),
        )

        viewModel = createViewModel()
        advanceUntilIdle()

        val ids = viewModel.uiState.value.pendingApprovals.map { it.approval.id }
        assertEquals(listOf("ap-1", "ap-2"), ids)
        assertTrue(viewModel.uiState.value.pendingApprovals.all { it.decision == null && !it.error })
    }

    @Test
    fun `a failed load leaves the chat without cards`() = runTest {
        coEvery { approvalRepository.getPending() } returns Result.failure(RuntimeException("offline"))

        viewModel = createViewModel()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.pendingApprovals.isEmpty())
    }

    @Test
    fun `an approval published on Mercure shows up without a reload`() = runTest {
        val stream = MutableSharedFlow<PendingApproval>(extraBufferCapacity = 4)
        every { approvalRepository.observe() } returns stream
        viewModel = createViewModel()
        advanceUntilIdle()

        stream.emit(approval("ap-9"))
        advanceUntilIdle()

        assertEquals(listOf("ap-9"), viewModel.uiState.value.pendingApprovals.map { it.approval.id })
    }

    @Test
    fun `a decision taken elsewhere takes the card away`() = runTest {
        val stream = MutableSharedFlow<PendingApproval>(extraBufferCapacity = 4)
        every { approvalRepository.observe() } returns stream
        coEvery { approvalRepository.getPending() } returns Result.success(listOf(approval("ap-1")))
        viewModel = createViewModel()
        advanceUntilIdle()

        stream.emit(approval("ap-1", status = PendingApproval.STATUS_APPROVED))
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.pendingApprovals.isEmpty())
    }

    @Test
    fun `an expired approval leaves the chat`() = runTest {
        val stream = MutableSharedFlow<PendingApproval>(extraBufferCapacity = 4)
        every { approvalRepository.observe() } returns stream
        coEvery { approvalRepository.getPending() } returns Result.success(listOf(approval("ap-1")))
        viewModel = createViewModel()
        advanceUntilIdle()

        stream.emit(approval("ap-1", status = PendingApproval.STATUS_EXPIRED))
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.pendingApprovals.isEmpty())
    }

    @Test
    fun `approving locks the card at once, then the confirmation removes it`() = runTest {
        coEvery { approvalRepository.getPending() } returns Result.success(listOf(approval("ap-1")))
        val answer = CompletableDeferred<Result<PendingApproval>>()
        coEvery { approvalRepository.approve("ap-1") } coAnswers { answer.await() }
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.approve("ap-1")
        runCurrent()

        val optimistic = viewModel.uiState.value.pendingApprovals.single()
        assertEquals(ApprovalDecision.APPROVE, optimistic.decision)
        assertEquals(PendingApproval.STATUS_PENDING, optimistic.approval.status)

        answer.complete(Result.success(approval("ap-1", status = PendingApproval.STATUS_APPROVED)))
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.pendingApprovals.isEmpty())
        coVerify(exactly = 1) { approvalRepository.approve("ap-1") }
    }

    @Test
    fun `an approved action that failed keeps its card, with the failure and no pending decision`() = runTest {
        coEvery { approvalRepository.getPending() } returns Result.success(listOf(approval("ap-1")))
        coEvery { approvalRepository.approve("ap-1") } returns Result.success(
            approval("ap-1", status = PendingApproval.STATUS_FAILED).copy(result = """{"error":"boom"}"""),
        )
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.approve("ap-1")
        advanceUntilIdle()

        val item = viewModel.uiState.value.pendingApprovals.single()
        assertEquals(PendingApproval.STATUS_FAILED, item.approval.status)
        assertNull(item.decision)

        viewModel.dismissApproval("ap-1")
        assertTrue(viewModel.uiState.value.pendingApprovals.isEmpty())
    }

    @Test
    fun `denying locks the card, then removes it`() = runTest {
        coEvery { approvalRepository.getPending() } returns Result.success(listOf(approval("ap-1")))
        val answer = CompletableDeferred<Result<PendingApproval>>()
        coEvery { approvalRepository.deny("ap-1") } coAnswers { answer.await() }
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.deny("ap-1")
        runCurrent()
        assertEquals(ApprovalDecision.DENY, viewModel.uiState.value.pendingApprovals.single().decision)

        answer.complete(Result.success(approval("ap-1", status = PendingApproval.STATUS_DENIED)))
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.pendingApprovals.isEmpty())
        coVerify(exactly = 0) { approvalRepository.approve(any()) }
    }

    @Test
    fun `a network error unlocks the card and says the answer did not go through`() = runTest {
        coEvery { approvalRepository.getPending() } returns Result.success(listOf(approval("ap-1")))
        coEvery { approvalRepository.approve("ap-1") } returns Result.failure(RuntimeException("timeout"))
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.approve("ap-1")
        advanceUntilIdle()

        val item = viewModel.uiState.value.pendingApprovals.single()
        assertNull(item.decision)
        assertTrue(item.error)
        assertTrue(item.approval.isPending)
    }

    @Test
    fun `the card can be answered again after a network error`() = runTest {
        coEvery { approvalRepository.getPending() } returns Result.success(listOf(approval("ap-1")))
        coEvery { approvalRepository.approve("ap-1") } returnsMany listOf(
            Result.failure(RuntimeException("timeout")),
            Result.success(approval("ap-1", status = PendingApproval.STATUS_APPROVED)),
        )
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.approve("ap-1")
        advanceUntilIdle()
        viewModel.approve("ap-1")
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.pendingApprovals.isEmpty())
        coVerify(exactly = 2) { approvalRepository.approve("ap-1") }
    }

    @Test
    fun `an action already decided or expired is dropped instead of offered again`() = runTest {
        coEvery { approvalRepository.getPending() } returns Result.success(listOf(approval("ap-1")))
        coEvery { approvalRepository.approve("ap-1") } returns Result.failure(ApprovalDecisionException(410))
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.approve("ap-1")
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.pendingApprovals.isEmpty())
    }

    @Test
    fun `a second tap while the answer is in flight is ignored`() = runTest {
        coEvery { approvalRepository.getPending() } returns Result.success(listOf(approval("ap-1")))
        val answer = CompletableDeferred<Result<PendingApproval>>()
        coEvery { approvalRepository.approve("ap-1") } coAnswers { answer.await() }
        coEvery { approvalRepository.deny("ap-1") } coAnswers { answer.await() }
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.approve("ap-1")
        runCurrent()
        viewModel.approve("ap-1")
        viewModel.deny("ap-1")
        runCurrent()

        coVerify(exactly = 1) { approvalRepository.approve("ap-1") }
        coVerify(exactly = 0) { approvalRepository.deny(any()) }
        assertEquals(ApprovalDecision.APPROVE, viewModel.uiState.value.pendingApprovals.single().decision)
    }

    @Test
    fun `a pending echo of the answer in flight does not unlock the card`() = runTest {
        val stream = MutableSharedFlow<PendingApproval>(extraBufferCapacity = 4)
        every { approvalRepository.observe() } returns stream
        coEvery { approvalRepository.getPending() } returns Result.success(listOf(approval("ap-1")))
        val answer = CompletableDeferred<Result<PendingApproval>>()
        coEvery { approvalRepository.approve("ap-1") } coAnswers { answer.await() }
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.approve("ap-1")
        runCurrent()
        stream.emit(approval("ap-1"))
        runCurrent()

        assertEquals(ApprovalDecision.APPROVE, viewModel.uiState.value.pendingApprovals.single().decision)
    }

    @Test
    fun `answering a card that is not there does nothing`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.approve("ghost")
        viewModel.deny("ghost")
        advanceUntilIdle()

        coVerify(exactly = 0) { approvalRepository.approve(any()) }
        coVerify(exactly = 0) { approvalRepository.deny(any()) }
    }

    // --- Answering a held action out loud, in the overlay (MAG-310) ---

    @Test
    fun `saying yes authorizes the open card`() = runTest {
        coEvery { approvalRepository.getPending() } returns Result.success(listOf(approval("ap-1")))
        coEvery { approvalRepository.approve("ap-1") } returns
            Result.success(approval("ap-1", status = PendingApproval.STATUS_APPROVED))
        viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.markApprovalAsked("ap-1")

        val taken = viewModel.answerApprovalByVoice("Oui, vas-y")
        advanceUntilIdle()

        assertTrue(taken)
        coVerify(exactly = 1) { approvalRepository.approve("ap-1") }
        assertTrue(viewModel.uiState.value.pendingApprovals.isEmpty())
    }

    @Test
    fun `saying no refuses the open card`() = runTest {
        coEvery { approvalRepository.getPending() } returns Result.success(listOf(approval("ap-1")))
        coEvery { approvalRepository.deny("ap-1") } returns
            Result.success(approval("ap-1", status = PendingApproval.STATUS_DENIED))
        viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.markApprovalAsked("ap-1")

        val taken = viewModel.answerApprovalByVoice("non merci")
        advanceUntilIdle()

        assertTrue(taken)
        coVerify(exactly = 1) { approvalRepository.deny("ap-1") }
        coVerify(exactly = 0) { approvalRepository.approve(any()) }
        assertTrue(viewModel.uiState.value.pendingApprovals.isEmpty())
    }

    @Test
    fun `any other sentence is not an answer and leaves the card open`() = runTest {
        coEvery { approvalRepository.getPending() } returns Result.success(listOf(approval("ap-1")))
        viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.markApprovalAsked("ap-1")

        val taken = viewModel.answerApprovalByVoice("oui mais attends")
        advanceUntilIdle()

        assertFalse(taken)
        coVerify(exactly = 0) { approvalRepository.approve(any()) }
        coVerify(exactly = 0) { approvalRepository.deny(any()) }
        val item = viewModel.uiState.value.pendingApprovals.single()
        assertNull(item.decision)
        assertTrue(item.approval.isPending)
    }

    @Test
    fun `a yes with nothing to authorize is a normal message`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        assertFalse(viewModel.answerApprovalByVoice("oui"))
        coVerify(exactly = 0) { approvalRepository.approve(any()) }
    }

    @Test
    fun `a spoken answer goes to the oldest card still open`() = runTest {
        coEvery { approvalRepository.getPending() } returns Result.success(
            listOf(approval("ap-2", createdAt = "2026-10-06T11:00:00Z"), approval("ap-1")),
        )
        coEvery { approvalRepository.approve("ap-1") } returns
            Result.success(approval("ap-1", status = PendingApproval.STATUS_APPROVED))
        viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.markApprovalAsked("ap-1")

        viewModel.answerApprovalByVoice("d'accord")
        advanceUntilIdle()

        coVerify(exactly = 1) { approvalRepository.approve("ap-1") }
        assertEquals(listOf("ap-2"), viewModel.uiState.value.pendingApprovals.map { it.approval.id })
    }

    @Test
    fun `a second yes while the first answer is in flight goes to the next card, not the same one twice`() = runTest {
        coEvery { approvalRepository.getPending() } returns Result.success(
            listOf(approval("ap-1"), approval("ap-2", createdAt = "2026-10-06T11:00:00Z")),
        )
        val answer = CompletableDeferred<Result<PendingApproval>>()
        coEvery { approvalRepository.approve("ap-1") } coAnswers { answer.await() }
        coEvery { approvalRepository.approve("ap-2") } coAnswers { answer.await() }
        viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.markApprovalAsked("ap-1")
        viewModel.markApprovalAsked("ap-2")

        viewModel.answerApprovalByVoice("oui")
        runCurrent()
        viewModel.answerApprovalByVoice("oui")
        runCurrent()

        coVerify(exactly = 1) { approvalRepository.approve("ap-1") }
        coVerify(exactly = 1) { approvalRepository.approve("ap-2") }
    }

    @Test
    fun `a yes to a card nobody read out is not an answer`() = runTest {
        coEvery { approvalRepository.getPending() } returns Result.success(listOf(approval("ap-1")))
        viewModel = createViewModel()
        advanceUntilIdle()

        assertFalse(viewModel.answerApprovalByVoice("oui"))
        coVerify(exactly = 0) { approvalRepository.approve(any()) }
        assertNull(viewModel.uiState.value.pendingApprovals.single().decision)
    }

    @Test
    fun `a yes answers the card that was read out, never another one`() = runTest {
        coEvery { approvalRepository.getPending() } returns Result.success(
            listOf(approval("ap-1"), approval("ap-2", createdAt = "2026-10-06T11:00:00Z")),
        )
        viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.markApprovalAsked("ap-2")

        assertFalse(viewModel.answerApprovalByVoice("oui"))
        coVerify(exactly = 0) { approvalRepository.approve(any()) }
    }

    @Test
    fun `the question carries what the repository resolves behind the id`() = runTest {
        val held = approval("ap-1")
        coEvery { approvalRepository.describe(held) } returns "Test validation"
        viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals("Je supprime l'événement Test validation ?", viewModel.questionFor(held))

    // --- MAG-342: delete a message, and follow the deletion of a thread ---

    private val threadMessages = listOf(
        ChatMessage(id = "m-1", role = "user", content = "Les courses", createdAt = "2026-02-15T10:00:00Z", contextId = "ctx-a"),
        ChatMessage(id = "m-2", role = "assistant", content = "Noté", createdAt = "2026-02-15T10:00:05Z", contextId = "ctx-a"),
        ChatMessage(id = "m-3", role = "user", content = "Le budget", createdAt = "2026-02-15T10:01:00Z", contextId = "ctx-b"),
        ChatMessage(id = "m-4", role = "assistant", content = "Tient", createdAt = "2026-02-15T10:01:05Z", contextId = "ctx-b"),
    )

    private fun shownIds() =
        viewModel.uiState.value.displayItems.filterIsInstance<ChatListItem.MessageItem>().map { it.message.id }

    private fun threadViewModel(): ChatViewModel {
        coEvery { repository.loadRecentMessages(any()) } returns threadMessages
        coEvery { repository.deleteMessage(any()) } returns Result.success(Unit)
        coEvery { repository.forgetMessages(any(), any()) } returns Unit
        return createViewModel().also { viewModel = it }
    }

    @Test
    fun `deleting a message removes it at once, offers the undo, and tells the server nothing yet`() = runTest {
        threadViewModel()
        advanceUntilIdle()

        viewModel.deleteMessage("m-2")

        assertEquals(listOf("m-1", "m-3", "m-4"), shownIds())
        assertEquals("m-2", viewModel.uiState.value.undoableMessageDeletion?.id)
        coVerify(exactly = 0) { repository.deleteMessage(any()) }
    }

    @Test
    fun `undoing a message deletion puts it back in place and the server never hears of it`() = runTest {
        threadViewModel()
        advanceUntilIdle()

        viewModel.deleteMessage("m-2")
        advanceTimeBy(ChatViewModel.UNDO_WINDOW_MS - 1_000)
        viewModel.undoMessageDeletion()
        advanceTimeBy(ChatViewModel.UNDO_WINDOW_MS)
        runCurrent()

        assertEquals(listOf("m-1", "m-2", "m-3", "m-4"), shownIds())
        assertNull(viewModel.uiState.value.undoableMessageDeletion)
        coVerify(exactly = 0) { repository.deleteMessage(any()) }
    }

    @Test
    fun `a message deletion is sent when the undo window closes`() = runTest {
        threadViewModel()
        advanceUntilIdle()

        viewModel.deleteMessage("m-2")
        advanceTimeBy(ChatViewModel.UNDO_WINDOW_MS - 1)
        runCurrent()
        coVerify(exactly = 0) { repository.deleteMessage(any()) }

        advanceTimeBy(2)
        runCurrent()

        coVerify(exactly = 1) { repository.deleteMessage("m-2") }
        assertEquals(listOf("m-1", "m-3", "m-4"), shownIds())
        assertNull(viewModel.uiState.value.undoableMessageDeletion)
        assertFalse(viewModel.uiState.value.messageDeleteFailed)
    }

    @Test
    fun `a failed message deletion brings the message back and says so`() = runTest {
        threadViewModel()
        advanceUntilIdle()
        coEvery { repository.deleteMessage("m-2") } returns Result.failure(RuntimeException("HTTP 500"))

        viewModel.deleteMessage("m-2")
        advanceTimeBy(ChatViewModel.UNDO_WINDOW_MS + 1)
        runCurrent()

        assertEquals(listOf("m-1", "m-2", "m-3", "m-4"), shownIds())
        assertTrue(viewModel.uiState.value.messageDeleteFailed)

        viewModel.consumeMessageDeleteFailed()
        assertFalse(viewModel.uiState.value.messageDeleteFailed)
    }

    @Test
    fun `a bubble still waiting for its stored id cannot be deleted`() = runTest {
        threadViewModel()
        advanceUntilIdle()
        every { repository.sendMessageStream(any()) } returns MutableSharedFlow<AgUiEvent>()
        viewModel.sendMessage("Bonjour")
        runCurrent()
        val pending = viewModel.uiState.value.messages.last()

        viewModel.deleteMessage(pending.id)
        advanceTimeBy(ChatViewModel.UNDO_WINDOW_MS + 1)
        runCurrent()

        assertNull(viewModel.uiState.value.undoableMessageDeletion)
        assertTrue(viewModel.uiState.value.messages.any { it.id == pending.id })
        coVerify(exactly = 0) { repository.deleteMessage(any()) }
    }

    @Test
    fun `confirming a thread deletion hides its messages, the chat keeps what remains`() = runTest {
        threadViewModel()
        advanceUntilIdle()

        viewModel.onThreadEvent(ThreadEvent.Hidden("ctx-a"))

        assertEquals(listOf("m-3", "m-4"), shownIds())
        // Hidden, not forgotten: « Annuler » must be able to bring them back.
        assertEquals(4, viewModel.uiState.value.messages.size)
    }

    @Test
    fun `undoing a thread deletion shows its messages again`() = runTest {
        threadViewModel()
        advanceUntilIdle()

        viewModel.onThreadEvent(ThreadEvent.Hidden("ctx-a"))
        viewModel.onThreadEvent(ThreadEvent.Restored("ctx-a"))

        assertEquals(listOf("m-1", "m-2", "m-3", "m-4"), shownIds())
    }

    @Test
    fun `a deleted thread takes its messages out of the chat for good, and the local copy with them`() = runTest {
        threadViewModel()
        advanceUntilIdle()

        viewModel.onThreadEvent(ThreadEvent.Hidden("ctx-b"))
        viewModel.onThreadEvent(ThreadEvent.Deleted("ctx-b"))
        advanceUntilIdle()

        assertEquals(listOf("m-1", "m-2"), shownIds())
        assertEquals(listOf("m-1", "m-2"), viewModel.uiState.value.messages.map { it.id })
        coVerify { repository.forgetMessages(emptyList(), "ctx-b") }
    }

    private fun echoIn(id: String, role: String, content: String, contextId: String) = MercureEvent(
        data = """{"id":"$id","role":"$role","content":"$content","createdAt":"2026-02-15T11:00:00Z","contextId":"$contextId"}""",
    )

    @Test
    fun `the messages of the current run, by the stream and by the echo, leave and come back with their thread`() = runTest {
        val topic = chatTopic()
        viewModel = createViewModel()
        advanceUntilIdle()
        every { repository.sendMessageStream(any()) } returns flow {
            emit(AgUiEvent.RunStarted(runId = "run-1"))
            emit(AgUiEvent.ContextUpdate(id = "ctx-1", label = "Courses", status = "active", action = "created"))
            emit(AgUiEvent.TextMessageStart(messageId = "resp-1"))
            emit(AgUiEvent.TextMessageContent(messageId = "resp-1", delta = "Salut !"))
            emit(AgUiEvent.TextMessageEnd(messageId = "resp-1"))
            // The echoes come last: the question's carries no thread, the answer's does, and
            // the answer is already there, streamed without one.
            topic.tryEmit(echo("u-9", "user", "Bonjour"))
            topic.tryEmit(echoIn("resp-1", "assistant", "Salut !", "ctx-1"))
            yield()
            emit(AgUiEvent.RunFinished(runId = "run-1"))
        }
        coEvery { repository.handleMercureMessage(any()) } returns Unit
        coEvery { repository.persistMessage(any()) } returns Unit

        val run = setOf("u-9", "resp-1")
        viewModel.sendMessage("Bonjour")
        advanceUntilIdle()
        assertEquals(listOf("u-9", "resp-1"), shownIds().filter { it in run })

        viewModel.onThreadEvent(ThreadEvent.Hidden("ctx-1"))
        assertTrue(shownIds().none { it in run })
        // Hidden, not forgotten: « Annuler » needs them.
        assertTrue(viewModel.uiState.value.messages.map { it.id }.containsAll(run))

        viewModel.onThreadEvent(ThreadEvent.Restored("ctx-1"))
        assertEquals(listOf("u-9", "resp-1"), shownIds().filter { it in run })
    }

    @Test
    fun `the echo of the question brings its thread to the pending bubble it replaces`() = runTest {
        val topic = chatTopic()
        viewModel = createViewModel()
        advanceUntilIdle()
        every { repository.sendMessageStream(any()) } returns flow {
            emit(AgUiEvent.RunStarted(runId = "run-1"))
            topic.tryEmit(echoIn("u-9", "user", "Bonjour", "ctx-1"))
            yield()
            emit(AgUiEvent.RunFinished(runId = "run-1"))
        }
        coEvery { repository.handleMercureMessage(any()) } returns Unit

        viewModel.sendMessage("Bonjour")
        advanceUntilIdle()

        assertEquals("ctx-1", viewModel.uiState.value.messages.single { it.id == "u-9" }.contextId)
    }

    @Test
    fun `deleting the only thread leaves the empty chat`() = runTest {
        coEvery { repository.loadRecentMessages(any()) } returns threadMessages.take(2)
        coEvery { repository.forgetMessages(any(), any()) } returns Unit
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onThreadEvent(ThreadEvent.Deleted("ctx-a"))
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.messages.isEmpty())
        assertTrue(viewModel.uiState.value.displayItems.none { it is ChatListItem.MessageItem })
    }

    @Test
    fun `a deletion published on the chat stream removes those messages and is never shown as one`() = runTest {
        val topic = chatTopic()
        threadViewModel()
        advanceUntilIdle()
        coEvery { repository.handleMercureMessage(any()) } returns Unit

        topic.emit(MercureEvent(data = """{"deleted":true,"contextId":null,"messageIds":["m-2","m-9"]}"""))
        advanceUntilIdle()

        assertEquals(listOf("m-1", "m-3", "m-4"), shownIds())
        // No bubble was built out of it.
        assertTrue(viewModel.uiState.value.messages.none { it.content.isEmpty() })
        coVerify(exactly = 0) { repository.handleMercureMessage(any()) }
        coVerify { repository.forgetMessages(match { it.toSet() == setOf("m-2", "m-9") }, null) }
    }

    @Test
    fun `a thread deletion published on the chat stream removes the thread's messages`() = runTest {
        val topic = chatTopic()
        threadViewModel()
        advanceUntilIdle()

        topic.emit(MercureEvent(data = """{"deleted":true,"contextId":"ctx-a","messageIds":["m-1","m-2"]}"""))
        advanceUntilIdle()

        assertEquals(listOf("m-3", "m-4"), shownIds())
    }

    @Test
    fun `the echo of a message this device already deleted changes nothing`() = runTest {
        val topic = chatTopic()
        threadViewModel()
        advanceUntilIdle()

        viewModel.deleteMessage("m-2")
        advanceTimeBy(ChatViewModel.UNDO_WINDOW_MS + 1)
        runCurrent()
        topic.emit(MercureEvent(data = """{"deleted":true,"contextId":null,"messageIds":["m-2"]}"""))
        advanceUntilIdle()

        assertEquals(listOf("m-1", "m-3", "m-4"), shownIds())
        assertFalse(viewModel.uiState.value.messageDeleteFailed)
    }

    @Test
    fun `a message deleted elsewhere while its undo is on screen ends the undo`() = runTest {
        val topic = chatTopic()
        threadViewModel()
        advanceUntilIdle()

        viewModel.deleteMessage("m-2")
        topic.emit(MercureEvent(data = """{"deleted":true,"contextId":null,"messageIds":["m-2"]}"""))
        advanceTimeBy(ChatViewModel.UNDO_WINDOW_MS + 1)
        runCurrent()

        assertNull(viewModel.uiState.value.undoableMessageDeletion)
        coVerify(exactly = 0) { repository.deleteMessage(any()) }
    }

    // --- MAG-319: an answer that is slow or lost by the call still reaches the screen ---

    private val sentQuestion = ChatMessage(id = "u-9", role = "user", content = "Quel temps demain ?", createdAt = "2026-02-15T11:00:00Z")
    private val storedAnswer = ChatMessage(id = "a-9", role = "assistant", content = "Grand soleil.", createdAt = "2026-02-15T11:00:12Z")

    @Test
    fun `an answer that takes 15 s is shown and offered to be read aloud`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()
        every { repository.sendMessageStream("Quel temps demain ?", any(), any()) } returns flow {
            delay(15_000)
            streamedAnswer("resp-1", "Grand soleil.").collect { emit(it) }
        }
        coEvery { repository.persistMessage(any()) } returns Unit

        viewModel.sendMessage("Quel temps demain ?")
        advanceTimeBy(14_000)
        runCurrent()
        assertTrue(viewModel.uiState.value.isLoading)

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertNull(state.failure)
        assertEquals("Grand soleil.", state.messages.last().content)
        assertEquals("Grand soleil.", state.replyToSpeak?.content)
    }

    @Test
    fun `a stream cut after the question was sent fetches the stored answer instead of sending it again`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()
        every { repository.sendMessageStream("Quel temps demain ?", any(), any()) } returns flowOf(AgUiEvent.Error("SocketTimeoutException: timeout"))
        coEvery { repository.fetchMessagesAfter("2026-02-15T10:00:05Z") } returns listOf(sentQuestion, storedAnswer)

        viewModel.sendMessage("Quel temps demain ?")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertNull(state.failure)
        assertEquals(listOf("msg-1", "msg-2", "u-9", "a-9"), state.messages.map { it.id })
        assertEquals("Grand soleil.", state.replyToSpeak?.content)
        coVerify(exactly = 0) { repository.sendMessage(any(), any()) }
    }

    @Test
    fun `an answer stored a few seconds after the stream was cut is waited for`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()
        every { repository.sendMessageStream("Quel temps demain ?", any(), any()) } returns flowOf(AgUiEvent.Error("SocketTimeoutException: timeout"))
        var fetches = 0
        coEvery { repository.fetchMessagesAfter(any()) } coAnswers {
            if (++fetches < 3) listOf(sentQuestion) else listOf(sentQuestion, storedAnswer)
        }

        viewModel.sendMessage("Quel temps demain ?")
        advanceUntilIdle()

        assertEquals(3, fetches)
        assertEquals("Grand soleil.", viewModel.uiState.value.replyToSpeak?.content)
        assertNull(viewModel.uiState.value.failure)
    }

    @Test
    fun `an answer that reaches Mercure while the app is looking for it ends the wait`() = runTest {
        val chat = MutableSharedFlow<MercureEvent>(extraBufferCapacity = 4)
        every { mercureService.subscribe(any()) } returns chat
        viewModel = createViewModel()
        advanceUntilIdle()
        every { repository.sendMessageStream("Quel temps demain ?", any(), any()) } returns flowOf(AgUiEvent.Error("SocketTimeoutException: timeout"))
        coEvery { repository.fetchMessagesAfter(any()) } returns emptyList()
        coEvery { repository.handleMercureMessage(any()) } returns Unit

        viewModel.sendMessage("Quel temps demain ?")
        advanceTimeBy(4_000)
        runCurrent()
        assertTrue(viewModel.uiState.value.isLoading)

        chat.emit(
            MercureEvent(data = """{"id":"a-9","role":"assistant","content":"Grand soleil.","createdAt":"2026-02-15T11:00:12Z"}"""),
        )
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isLoading)
        assertNull(viewModel.uiState.value.failure)
        assertEquals("Grand soleil.", viewModel.uiState.value.replyToSpeak?.content)
    }

    @Test
    fun `no answer anywhere ends in an error in the thread, not in silence`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()
        every { repository.sendMessageStream("Quel temps demain ?", any(), any()) } returns flowOf(AgUiEvent.Error("HTTP 502"))
        coEvery { repository.fetchMessagesAfter(any()) } returns listOf(sentQuestion)

        viewModel.sendMessage("Quel temps demain ?")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertNull(state.replyToSpeak)
        assertTrue(state.failure != null)
        assertTrue(state.displayItems.any { it is ChatListItem.Failure })
    }

    @Test
    fun `a request that fails both ways shows the error`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()
        every { repository.sendMessageStream("Hello", any(), any()) } returns flow { throw RuntimeException("Stream error") }
        coEvery { repository.sendMessage("Hello") } throws RuntimeException("Network error")
        coEvery { repository.fetchMessagesAfter(any()) } throws RuntimeException("Network error")

        viewModel.sendMessage("Hello")
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.displayItems.any { it is ChatListItem.Failure })
        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test
    fun `an answer lost by the fallback call is picked up from the server`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()
        every { repository.sendMessageStream("Quel temps demain ?", any(), any()) } returns flow { throw java.net.ConnectException("refused") }
        coEvery { repository.sendMessage("Quel temps demain ?") } throws RuntimeException("timeout")
        coEvery { repository.fetchMessagesAfter(any()) } returns listOf(sentQuestion, storedAnswer)

        viewModel.sendMessage("Quel temps demain ?")
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.failure)
        assertEquals("Grand soleil.", viewModel.uiState.value.replyToSpeak?.content)
    }

    @Test
    fun `the next question clears the error of the last one`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()
        every { repository.sendMessageStream("Hello", any(), any()) } returns flowOf(AgUiEvent.Error("HTTP 502"))
        coEvery { repository.fetchMessagesAfter(any()) } returns emptyList()
        viewModel.sendMessage("Hello")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.failure != null)

        every { repository.sendMessageStream("Encore", any(), any()) } returns streamedAnswer("resp-2", "Oui.")
        coEvery { repository.persistMessage(any()) } returns Unit
        viewModel.sendMessage("Encore")
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.failure)
    }

    // --- MAG-363: a failure is a state of the question, with « Réessayer » — never a bubble of Maggie's ---

    @Test
    fun `no answer is a mention under the question, not a message of Maggie`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()
        every { repository.sendMessageStream("Quel temps demain ?", any(), any()) } returns flowOf(AgUiEvent.Error("HTTP 502"))
        coEvery { repository.fetchMessagesAfter(any()) } returns listOf(sentQuestion)

        viewModel.sendMessage("Quel temps demain ?")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.failure != null)
        assertTrue(state.messages.none { it.role == "assistant" && it.content == state.failure })
        assertEquals(1, state.messages.count { it.content == "Quel temps demain ?" })
        assertTrue(state.displayItems.last() is ChatListItem.Failure)
        assertEquals(
            0,
            state.displayItems.count { it is ChatListItem.MessageItem && it.message.content == state.failure },
        )
    }

    @Test
    fun `retry sends the same text again under the same key, and the answer lands under the question`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()
        val keys = mutableListOf<String>()
        var calls = 0
        every { repository.sendMessageStream("Quel temps demain ?", any(), capture(keys)) } answers {
            if (++calls == 1) flowOf(AgUiEvent.Error("HTTP 502")) else streamedAnswer("resp-1", "Grand soleil.")
        }
        coEvery { repository.fetchMessagesAfter(any()) } returns emptyList()
        coEvery { repository.persistMessage(any()) } returns Unit

        viewModel.sendMessage("Quel temps demain ?")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.failure != null)

        viewModel.retry()
        advanceUntilIdle()

        assertEquals(2, keys.size)
        assertEquals(keys[0], keys[1])
        val state = viewModel.uiState.value
        assertNull(state.failure)
        assertFalse(state.isLoading)
        assertEquals(listOf("user", "assistant"), state.messages.takeLast(2).map { it.role })
        assertEquals(1, state.messages.count { it.content == "Quel temps demain ?" })
        assertEquals("Grand soleil.", state.messages.last().content)
        assertTrue(state.displayItems.none { it is ChatListItem.Failure })
    }

    @Test
    fun `retry sends the screenshot again with the question it came with`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()
        val images = mutableListOf<ChatImage?>()
        var calls = 0
        every { repository.sendMessageStream("c'est quoi ?", any(), any(), captureNullable(images)) } answers {
            if (++calls == 1) flowOf(AgUiEvent.Error("HTTP 502")) else streamedAnswer("resp-1", "Une cafetière.")
        }
        coEvery { repository.fetchMessagesAfter(any()) } returns emptyList()
        coEvery { repository.persistMessage(any()) } returns Unit

        viewModel.sendMessage("c'est quoi ?", null, jpeg)
        advanceUntilIdle()
        viewModel.retry()
        advanceUntilIdle()

        val expected = ChatImage.jpeg(jpeg)
        assertEquals(listOf(expected, expected), images)
    }

    @Test
    fun `a second failure shows the mention and its button again`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()
        every { repository.sendMessageStream("Hello", any(), any()) } returns flowOf(AgUiEvent.Error("HTTP 502"))
        coEvery { repository.fetchMessagesAfter(any()) } returns emptyList()

        viewModel.sendMessage("Hello")
        advanceUntilIdle()
        viewModel.retry()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.failure != null)
        assertTrue(viewModel.uiState.value.displayItems.last() is ChatListItem.Failure)
        verify(exactly = 2) { repository.sendMessageStream("Hello", any(), any()) }

        viewModel.retry()
        advanceUntilIdle()
        verify(exactly = 3) { repository.sendMessageStream("Hello", any(), any()) }
    }

    @Test
    fun `retry with nothing to retry does nothing`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.retry()
        advanceUntilIdle()

        verify(exactly = 0) { repository.sendMessageStream(any(), any(), any()) }
    }

    @Test
    fun `an answer that arrives while the mention is shown removes it, and retry is gone`() = runTest {
        val chat = MutableSharedFlow<MercureEvent>(extraBufferCapacity = 4)
        every { mercureService.subscribe(any()) } returns chat
        viewModel = createViewModel()
        advanceUntilIdle()
        every { repository.sendMessageStream("Hello", any(), any()) } returns flowOf(AgUiEvent.Error("HTTP 502"))
        coEvery { repository.fetchMessagesAfter(any()) } returns emptyList()
        coEvery { repository.handleMercureMessage(any()) } returns Unit

        viewModel.sendMessage("Hello")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.failure != null)

        chat.emit(MercureEvent(data = """{"id":"a-9","role":"assistant","content":"Salut.","createdAt":"2026-02-15T11:00:12Z"}"""))
        advanceUntilIdle()
        viewModel.retry()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.failure)
        verify(exactly = 1) { repository.sendMessageStream("Hello", any(), any()) }
    }

    // --- Cutting Maggie off (MAG-223) ---

    private fun answerInProgress(vararg events: AgUiEvent): Channel<AgUiEvent> {
        val channel = Channel<AgUiEvent>(Channel.UNLIMITED)
        events.forEach { channel.trySend(it) }
        every { repository.sendMessageStream(any(), any(), any()) } returns channel.receiveAsFlow()
        return channel
    }

    private fun halfWrittenStory() = answerInProgress(
        AgUiEvent.RunStarted(runId = "run-1"),
        AgUiEvent.TextMessageStart(messageId = "resp-1"),
        AgUiEvent.TextMessageContent(messageId = "resp-1", delta = "Il était une fois"),
    )

    private fun interruptedAs(content: String) =
        ChatMessage(id = "resp-1", role = "assistant", content = content, createdAt = "2026-02-15T11:00:00Z")

    @Test
    fun `interrupting an answer being written keeps what was shown and stops the stream`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()
        halfWrittenStory()
        coEvery { repository.interruptChat(any(), any()) } returns interruptedAs("Il était une fois")
        viewModel.sendMessage("Raconte")
        advanceUntilIdle()

        viewModel.interrupt()
        advanceUntilIdle()

        coVerify { repository.interruptChat("resp-1", "Il était une fois") }
        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals("", state.streamingText)
        assertNull(state.streamingMessageId)
        assertEquals("Il était une fois", state.messages.last().content)
        assertEquals("assistant", state.messages.last().role)
    }

    @Test
    fun `what the stream says after the interruption never shows nor gets read`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()
        val stream = halfWrittenStory()
        coEvery { repository.interruptChat(any(), any()) } returns interruptedAs("Il était une fois")
        coEvery { repository.persistMessage(any()) } returns Unit
        viewModel.sendMessage("Raconte")
        advanceUntilIdle()
        viewModel.interrupt()
        advanceUntilIdle()

        stream.trySend(AgUiEvent.TextMessageContent(messageId = "resp-1", delta = " un roi. FIN"))
        stream.trySend(AgUiEvent.TextMessageEnd(messageId = "resp-1"))
        stream.trySend(AgUiEvent.RunFinished(runId = "run-1"))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNull(state.replyToSpeak)
        assertEquals("", state.streamingText)
        assertTrue(state.messages.none { it.content.contains("FIN") })
        assertEquals(1, state.messages.count { it.role == "assistant" && it.content == "Il était une fois" })
    }

    @Test
    fun `an answer whose text is not on screen is reported as saying nothing`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()
        halfWrittenStory()
        coEvery { repository.interruptChat(any(), any()) } returns interruptedAs("…")
        viewModel.sendMessage("Raconte")
        advanceUntilIdle()

        viewModel.interrupt(heard = null, streamingIsShown = false)
        advanceUntilIdle()

        coVerify { repository.interruptChat("resp-1", "") }
    }

    @Test
    fun `interrupting before the first word has no message id yet`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()
        answerInProgress(AgUiEvent.RunStarted(runId = "run-1"))
        coEvery { repository.interruptChat(any(), any()) } returns interruptedAs("…")
        viewModel.sendMessage("Raconte")
        advanceUntilIdle()

        viewModel.interrupt()
        advanceUntilIdle()

        coVerify { repository.interruptChat(null, "") }
        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test
    fun `interrupting a reply being read aloud shortens it to what was heard`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()
        every { repository.sendMessageStream("Raconte", any(), any()) } returns streamedAnswer("resp-1", "Il était une fois un roi.")
        coEvery { repository.persistMessage(any()) } returns Unit
        coEvery { repository.interruptChat(any(), any()) } returns interruptedAs("Il était une fois")
        viewModel.sendMessage("Raconte")
        advanceUntilIdle()
        viewModel.onReplySpoken()

        viewModel.interrupt(heard = "Il était une fois")
        advanceUntilIdle()

        coVerify { repository.interruptChat("resp-1", "Il était une fois") }
        assertEquals(
            listOf("Il était une fois"),
            viewModel.uiState.value.messages.filter { it.id == "resp-1" }.map { it.content },
        )
    }

    @Test
    fun `an approval question spoken after a reply is what the mic cuts, not the reply`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()
        every { repository.sendMessageStream("Raconte", any(), any()) } returns streamedAnswer("resp-1", "Il était une fois un roi.")
        coEvery { repository.persistMessage(any()) } returns Unit
        viewModel.sendMessage("Raconte")
        advanceUntilIdle()
        viewModel.onReplySpoken()

        viewModel.markApprovalAsked("ap-1")
        viewModel.interrupt(heard = "Tu veux")
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.interruptChat(any(), any()) }
        assertEquals(
            listOf("Il était une fois un roi."),
            viewModel.uiState.value.messages.filter { it.id == "resp-1" }.map { it.content },
        )
    }

    @Test
    fun `a reply not yet started when the mic is pressed is reported as saying nothing`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()
        every { repository.sendMessageStream("Raconte", any(), any()) } returns streamedAnswer("resp-1", "Il était une fois un roi.")
        coEvery { repository.persistMessage(any()) } returns Unit
        coEvery { repository.interruptChat(any(), any()) } returns interruptedAs("…")
        viewModel.sendMessage("Raconte")
        advanceUntilIdle()
        assertEquals("resp-1", viewModel.uiState.value.replyToSpeak?.id)

        viewModel.interrupt()
        advanceUntilIdle()

        coVerify { repository.interruptChat("resp-1", "") }
        assertNull(viewModel.uiState.value.replyToSpeak)
    }

    @Test
    fun `interrupting when nothing is going on reports nothing`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.interrupt()
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.interruptChat(any(), any()) }
    }

    @Test
    fun `the next request waits for the interruption to be recorded`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()
        halfWrittenStory()
        val recorded = CompletableDeferred<ChatMessage>()
        coEvery { repository.interruptChat(any(), any()) } coAnswers { recorded.await() }
        viewModel.sendMessage("Raconte")
        advanceUntilIdle()
        viewModel.interrupt()
        advanceUntilIdle()
        every { repository.sendMessageStream("Plutôt une blague", any(), any()) } returns streamedAnswer("resp-2", "Toc toc.")
        coEvery { repository.persistMessage(any()) } returns Unit

        viewModel.sendMessage("Plutôt une blague")
        advanceUntilIdle()
        verify(exactly = 0) { repository.sendMessageStream("Plutôt une blague", any(), any()) }

        recorded.complete(interruptedAs("Il était une fois"))
        advanceUntilIdle()

        verify(exactly = 1) { repository.sendMessageStream("Plutôt une blague", any(), any()) }
        assertEquals("resp-2", viewModel.uiState.value.replyToSpeak?.id)
    }

    @Test
    fun `an interruption the server could not record leaves the chat usable`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()
        halfWrittenStory()
        coEvery { repository.interruptChat(any(), any()) } throws RuntimeException("offline")
        viewModel.sendMessage("Raconte")
        advanceUntilIdle()

        viewModel.interrupt()
        advanceUntilIdle()
        every { repository.sendMessageStream("Salut", any(), any()) } returns streamedAnswer("resp-2", "Toc toc.")
        coEvery { repository.persistMessage(any()) } returns Unit
        viewModel.sendMessage("Salut")
        advanceUntilIdle()

        assertEquals("resp-2", viewModel.uiState.value.replyToSpeak?.id)
    }

    @Test
    fun `the echo of an answer cut short replaces the whole one already shown`() = runTest {
        val topic = chatTopic()
        viewModel = createViewModel()
        advanceUntilIdle()
        coEvery { repository.handleMercureMessage(any()) } returns Unit
        topic.tryEmit(echo("a-9", "assistant", "Il était une fois un roi."))
        advanceUntilIdle()

        topic.tryEmit(echo("a-9", "assistant", "Il était une fois"))
        advanceUntilIdle()

        assertEquals(
            listOf("Il était une fois"),
            viewModel.uiState.value.messages.filter { it.id == "a-9" }.map { it.content },
        )
    }
}

package com.maggie.app.ui.screens.chat

import com.maggie.app.data.api.ChatResponse
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.model.ChatMessage
import com.maggie.app.data.repository.ChatRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var repository: ChatRepository
    private lateinit var mercureService: MercureService
    private lateinit var viewModel: ChatViewModel
    private val messagesFlow = MutableSharedFlow<List<ChatMessage>>(replay = 1)
    private var nextId = 1L

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        repository = mockk()
        mercureService = mockk()
        every { mercureService.subscribe(any()) } returns emptyFlow()
        every { repository.observeMessages() } returns messagesFlow
        coEvery { repository.saveMessage(any()) } coAnswers {
            val msg = firstArg<ChatMessage>()
            msg.copy(id = nextId++)
        }
        messagesFlow.tryEmit(emptyList())
        viewModel = ChatViewModel(repository, mercureService)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `sendMessage success adds user and assistant messages`() = runTest {
        coEvery { repository.sendMessage("Hello") } returns Result.success(
            ChatResponse(response = "Hi there!")
        )

        viewModel.sendMessage("Hello")
        advanceUntilIdle()

        // Simulate Room Flow emitting the persisted messages
        val userMsg = ChatMessage(id = 1, role = "user", content = "Hello")
        val assistantMsg = ChatMessage(id = 2, role = "assistant", content = "Hi there!")
        messagesFlow.tryEmit(listOf(userMsg, assistantMsg))
        advanceUntilIdle()

        val finalState = viewModel.uiState.value
        assertEquals(2, finalState.messages.size)
        assertEquals("user", finalState.messages[0].role)
        assertEquals("Hello", finalState.messages[0].content)
        assertEquals("assistant", finalState.messages[1].role)
        assertEquals("Hi there!", finalState.messages[1].content)
        assertFalse(finalState.isLoading)
    }

    @Test
    fun `sendMessage failure adds error message`() = runTest {
        coEvery { repository.sendMessage("Hello") } returns Result.failure(
            RuntimeException("Network error")
        )

        viewModel.sendMessage("Hello")
        advanceUntilIdle()

        // Simulate Room Flow emitting the persisted messages
        val userMsg = ChatMessage(id = 1, role = "user", content = "Hello")
        val errorMsg = ChatMessage(id = 2, role = "assistant", content = "Erreur : impossible de joindre Maggie.")
        messagesFlow.tryEmit(listOf(userMsg, errorMsg))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(2, state.messages.size)
        assertEquals("assistant", state.messages[1].role)
        assertTrue(state.messages[1].content.contains("Erreur"))
        assertFalse(state.isLoading)
    }

    @Test
    fun `sendMessage with blank text does nothing`() = runTest {
        viewModel.sendMessage("   ")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.messages.isEmpty())
        assertFalse(state.isLoading)
    }
}

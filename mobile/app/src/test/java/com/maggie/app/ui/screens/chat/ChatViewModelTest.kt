package com.maggie.app.ui.screens.chat

import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.model.ChatMessage
import com.maggie.app.data.repository.ChatRepository
import io.mockk.coEvery
import io.mockk.coVerify
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

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        repository = mockk()
        mercureService = mockk()
        every { mercureService.subscribe(any()) } returns emptyFlow()
        every { repository.observeMessages() } returns messagesFlow
        coEvery { repository.syncMessages() } returns Unit
        messagesFlow.tryEmit(emptyList())
        viewModel = ChatViewModel(repository, mercureService)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `sendMessage success posts to agent and clears loading`() = runTest {
        val userMsg = ChatMessage(id = "abc-123", role = "user", content = "Hello", createdAt = "2026-02-15T00:00:00+00:00")
        val assistantMsg = ChatMessage(id = "abc-456", role = "assistant", content = "Hi!", createdAt = "2026-02-15T00:00:01+00:00")
        coEvery { repository.sendMessage("Hello") } returns listOf(userMsg, assistantMsg)

        viewModel.sendMessage("Hello")
        advanceUntilIdle()

        coVerify { repository.sendMessage("Hello") }
        // Loading clears after response returns
        assertFalse(viewModel.uiState.value.isLoading)

        // Simulate Room Flow emitting the persisted messages
        messagesFlow.tryEmit(listOf(userMsg, assistantMsg))
        advanceUntilIdle()

        assertEquals(2, viewModel.uiState.value.messages.size)
        assertEquals("user", viewModel.uiState.value.messages[0].role)
        assertEquals("assistant", viewModel.uiState.value.messages[1].role)
    }

    @Test
    fun `sendMessage failure clears loading`() = runTest {
        coEvery { repository.sendMessage("Hello") } throws RuntimeException("Network error")

        viewModel.sendMessage("Hello")
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test
    fun `sendMessage with blank text does nothing`() = runTest {
        viewModel.sendMessage("   ")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.messages.isEmpty())
        assertFalse(state.isLoading)
    }

    @Test
    fun `init syncs messages from server`() = runTest {
        advanceUntilIdle()
        coVerify { repository.syncMessages() }
    }
}

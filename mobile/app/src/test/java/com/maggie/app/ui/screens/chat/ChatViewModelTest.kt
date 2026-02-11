package com.maggie.app.ui.screens.chat

import com.maggie.app.data.api.ChatResponse
import com.maggie.app.data.repository.ChatRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
    private lateinit var viewModel: ChatViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        repository = mockk()
        viewModel = ChatViewModel(repository)
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

        // After sending, user message should be added and loading=true
        val stateAfterSend = viewModel.uiState.value
        assertEquals(1, stateAfterSend.messages.size)
        assertEquals("user", stateAfterSend.messages[0].role)
        assertEquals("Hello", stateAfterSend.messages[0].content)
        assertTrue(stateAfterSend.isLoading)

        // After coroutine completes
        advanceUntilIdle()

        val finalState = viewModel.uiState.value
        assertEquals(2, finalState.messages.size)
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

        val state = viewModel.uiState.value
        assertEquals(2, state.messages.size)
        assertEquals("assistant", state.messages[1].role)
        assertTrue(state.messages[1].content.contains("Error"))
        assertFalse(state.isLoading)
    }

    @Test
    fun `sendMessage with blank text does nothing`() = runTest {
        viewModel.sendMessage("   ")

        val state = viewModel.uiState.value
        assertTrue(state.messages.isEmpty())
        assertFalse(state.isLoading)
    }
}

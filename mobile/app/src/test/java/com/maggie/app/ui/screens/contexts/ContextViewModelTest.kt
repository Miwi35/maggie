package com.maggie.app.ui.screens.contexts

import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.model.Context
import com.maggie.app.data.repository.ContextRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
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
class ContextViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var contextRepository: ContextRepository
    private lateinit var mercureService: MercureService
    private lateinit var authRepository: AuthRepository
    private lateinit var viewModel: ContextViewModel

    private val sampleContexts = listOf(
        Context(id = "ctx-1", label = "Courses de la semaine", status = "active"),
        Context(id = "ctx-2", label = "Recettes du soir", status = "dormant"),
        Context(id = "ctx-3", label = "Rendez-vous dentiste", status = "active"),
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        contextRepository = mockk()
        mercureService = mockk()
        authRepository = mockk()
        every { mercureService.subscribe(any()) } returns emptyFlow()
        coEvery { authRepository.getUserId() } returns "user-1"
    }

    private fun createViewModel(): ContextViewModel {
        return ContextViewModel(contextRepository, mercureService, authRepository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial load populates contexts and activeCount`() = runTest {
        coEvery { contextRepository.getContexts() } returns Result.success(sampleContexts)
        viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(3, state.contexts.size)
        assertEquals(2, state.activeCount) // ctx-1 and ctx-3 are active
        assertFalse(state.isLoading)
        assertNull(state.error)
    }

    @Test
    fun `activeCount only counts active contexts`() = runTest {
        val contexts = listOf(
            Context(id = "ctx-1", label = "A", status = "active"),
            Context(id = "ctx-2", label = "B", status = "dormant"),
            Context(id = "ctx-3", label = "C", status = "closed"),
        )
        coEvery { contextRepository.getContexts() } returns Result.success(contexts)
        viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.activeCount)
    }

    @Test
    fun `empty list results in zero activeCount`() = runTest {
        coEvery { contextRepository.getContexts() } returns Result.success(emptyList())
        viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.contexts.isEmpty())
        assertEquals(0, state.activeCount)
        assertFalse(state.isLoading)
    }

    @Test
    fun `error handling sets error message`() = runTest {
        coEvery { contextRepository.getContexts() } returns Result.failure(RuntimeException("Server error"))
        viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("Server error", state.error)
        assertFalse(state.isLoading)
    }

    @Test
    fun `handleStreamUpdate upserts new context`() = runTest {
        coEvery { contextRepository.getContexts() } returns Result.success(sampleContexts)
        viewModel = createViewModel()
        advanceUntilIdle()

        val newContext = Context(id = "ctx-new", label = "Nouveau contexte", status = "active")
        viewModel.handleStreamUpdate(newContext)

        val state = viewModel.uiState.value
        assertEquals(4, state.contexts.size)
        assertEquals("ctx-new", state.contexts.first().id) // Added at front
        assertEquals(3, state.activeCount) // 2 original active + 1 new
    }

    @Test
    fun `handleStreamUpdate replaces existing context`() = runTest {
        coEvery { contextRepository.getContexts() } returns Result.success(sampleContexts)
        viewModel = createViewModel()
        advanceUntilIdle()

        // Update ctx-2 from dormant to active
        val updated = Context(id = "ctx-2", label = "Recettes du soir", status = "active")
        viewModel.handleStreamUpdate(updated)

        val state = viewModel.uiState.value
        assertEquals(3, state.contexts.size) // Same count
        assertEquals("active", state.contexts.find { it.id == "ctx-2" }?.status)
        assertEquals(3, state.activeCount) // Now all 3 are active
    }

    @Test
    fun `mercure subscribes to correct topic`() = runTest {
        coEvery { contextRepository.getContexts() } returns Result.success(emptyList())
        viewModel = createViewModel()
        advanceUntilIdle()

        io.mockk.verify { mercureService.subscribe("/contexts/user-1") }
    }
}

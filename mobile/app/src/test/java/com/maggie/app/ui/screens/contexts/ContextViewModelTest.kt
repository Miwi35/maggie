package com.maggie.app.ui.screens.contexts

import android.util.Log
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.model.Context
import com.maggie.app.data.repository.ContextRepository
import com.maggie.app.data.mercure.MercureEvent
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
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
    fun `initial load populates contexts`() = runTest {
        coEvery { contextRepository.getContexts() } returns Result.success(sampleContexts)
        viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(3, state.contexts.size)
        assertFalse(state.isLoading)
        assertNull(state.error)
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
    }

    @Test
    fun `refresh brings the counts of a thread opened after the first load`() = runTest {
        coEvery { contextRepository.getContexts() } returns Result.success(sampleContexts)
        viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.handleStreamUpdate(Context(id = "ctx-new", label = "Nouveau contexte", status = "active"))
        assertEquals(0, viewModel.uiState.value.contexts.first().messageCount)

        coEvery { contextRepository.getContexts() } returns Result.success(
            listOf(Context(id = "ctx-new", label = "Nouveau contexte", status = "active", messageCount = 4)) + sampleContexts,
        )
        viewModel.refresh()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(4, state.contexts.first { it.id == "ctx-new" }.messageCount)
        assertFalse(state.isLoading)
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
    }

    @Test
    fun `mercure subscribes to correct topic`() = runTest {
        coEvery { contextRepository.getContexts() } returns Result.success(emptyList())
        viewModel = createViewModel()
        advanceUntilIdle()

        io.mockk.verify { mercureService.subscribe("/contexts/user-1") }
    }

    // --- MAG-342: delete a thread — confirm, then « Annuler » for 6 s, the server hearing nothing meanwhile ---

    private fun loadedViewModel(contexts: List<Context> = sampleContexts): ContextViewModel {
        coEvery { contextRepository.getContexts() } returns Result.success(contexts)
        coEvery { contextRepository.deleteContext(any()) } returns Result.success(Unit)
        return createViewModel().also { viewModel = it }
    }

    private fun TestScope.confirmDeletionOf(contextId: String) {
        viewModel.requestDeletion(sampleContexts.first { it.id == contextId })
        viewModel.confirmDeletion()
        runCurrent()
    }

    @Test
    fun `requesting a deletion asks for confirmation and removes nothing yet`() = runTest {
        loadedViewModel()
        advanceUntilIdle()

        viewModel.requestDeletion(sampleContexts[1])

        val state = viewModel.uiState.value
        assertEquals("ctx-2", state.deletionToConfirm?.id)
        assertEquals(3, state.contexts.size)
        assertNull(state.undoableDeletion)
        coVerify(exactly = 0) { contextRepository.deleteContext(any()) }
    }

    @Test
    fun `cancelling the confirmation keeps the thread and sends nothing`() = runTest {
        loadedViewModel()
        advanceUntilIdle()

        viewModel.requestDeletion(sampleContexts[1])
        viewModel.cancelDeletionRequest()
        advanceTimeBy(ContextViewModel.UNDO_WINDOW_MS + 1)
        runCurrent()

        val state = viewModel.uiState.value
        assertNull(state.deletionToConfirm)
        assertEquals(3, state.contexts.size)
        coVerify(exactly = 0) { contextRepository.deleteContext(any()) }
    }

    @Test
    fun `confirming removes the thread at once, offers the undo, and tells the chat`() = runTest {
        loadedViewModel()
        advanceUntilIdle()
        val events = mutableListOf<ThreadEvent>()
        backgroundScope.launch { viewModel.threadEvents.toList(events) }
        runCurrent()

        confirmDeletionOf("ctx-2")

        val state = viewModel.uiState.value
        assertEquals(listOf("ctx-1", "ctx-3"), state.contexts.map { it.id })
        assertNull(state.deletionToConfirm)
        assertEquals("ctx-2", state.undoableDeletion?.id)
        assertEquals(listOf<ThreadEvent>(ThreadEvent.Hidden("ctx-2")), events)
        // Deferred: the server has heard nothing.
        coVerify(exactly = 0) { contextRepository.deleteContext(any()) }
    }

    @Test
    fun `undoing puts the thread back where it was and the server never hears of it`() = runTest {
        loadedViewModel()
        advanceUntilIdle()
        val events = mutableListOf<ThreadEvent>()
        backgroundScope.launch { viewModel.threadEvents.toList(events) }
        runCurrent()

        confirmDeletionOf("ctx-2")
        advanceTimeBy(ContextViewModel.UNDO_WINDOW_MS - 1_000)
        viewModel.undoDeletion()
        advanceTimeBy(ContextViewModel.UNDO_WINDOW_MS)
        runCurrent()

        val state = viewModel.uiState.value
        assertEquals(listOf("ctx-1", "ctx-2", "ctx-3"), state.contexts.map { it.id })
        assertNull(state.undoableDeletion)
        assertFalse(state.deleteFailed)
        assertEquals(listOf(ThreadEvent.Hidden("ctx-2"), ThreadEvent.Restored("ctx-2")), events)
        coVerify(exactly = 0) { contextRepository.deleteContext(any()) }
    }

    @Test
    fun `the deletion is sent when the undo window closes, not before`() = runTest {
        loadedViewModel()
        advanceUntilIdle()
        val events = mutableListOf<ThreadEvent>()
        backgroundScope.launch { viewModel.threadEvents.toList(events) }
        runCurrent()

        confirmDeletionOf("ctx-2")
        advanceTimeBy(ContextViewModel.UNDO_WINDOW_MS - 1)
        runCurrent()
        coVerify(exactly = 0) { contextRepository.deleteContext(any()) }

        advanceTimeBy(2)
        runCurrent()

        coVerify(exactly = 1) { contextRepository.deleteContext("ctx-2") }
        val state = viewModel.uiState.value
        assertEquals(listOf("ctx-1", "ctx-3"), state.contexts.map { it.id })
        assertNull(state.undoableDeletion)
        assertFalse(state.deleteFailed)
        assertEquals(ThreadEvent.Deleted("ctx-2"), events.last())
    }

    @Test
    fun `a failed deletion brings the thread back and says so`() = runTest {
        loadedViewModel()
        advanceUntilIdle()
        coEvery { contextRepository.deleteContext("ctx-2") } returns Result.failure(RuntimeException("HTTP 500"))
        val events = mutableListOf<ThreadEvent>()
        backgroundScope.launch { viewModel.threadEvents.toList(events) }
        runCurrent()

        confirmDeletionOf("ctx-2")
        advanceTimeBy(ContextViewModel.UNDO_WINDOW_MS + 1)
        runCurrent()

        val state = viewModel.uiState.value
        assertEquals(listOf("ctx-1", "ctx-2", "ctx-3"), state.contexts.map { it.id })
        assertTrue(state.deleteFailed)
        assertNull(state.undoableDeletion)
        // A French message is the screen's; the technical one stays out of the state.
        assertNull(state.error)
        assertEquals(ThreadEvent.Restored("ctx-2"), events.last())

        viewModel.consumeDeleteFailed()
        assertFalse(viewModel.uiState.value.deleteFailed)
    }

    @Test
    fun `a second deletion makes the first one final`() = runTest {
        loadedViewModel()
        advanceUntilIdle()

        confirmDeletionOf("ctx-1")
        confirmDeletionOf("ctx-3")
        runCurrent()

        coVerify(exactly = 1) { contextRepository.deleteContext("ctx-1") }
        coVerify(exactly = 0) { contextRepository.deleteContext("ctx-3") }
        assertEquals("ctx-3", viewModel.uiState.value.undoableDeletion?.id)

        advanceTimeBy(ContextViewModel.UNDO_WINDOW_MS + 1)
        runCurrent()

        coVerify(exactly = 1) { contextRepository.deleteContext("ctx-3") }
        assertEquals(listOf("ctx-2"), viewModel.uiState.value.contexts.map { it.id })
    }

    @Test
    fun `a refresh during the undo window does not bring the thread back`() = runTest {
        loadedViewModel()
        advanceUntilIdle()

        confirmDeletionOf("ctx-2")
        viewModel.refresh()
        runCurrent()

        val state = viewModel.uiState.value
        assertEquals(listOf("ctx-1", "ctx-3"), state.contexts.map { it.id })
        assertEquals("ctx-2", state.undoableDeletion?.id)
    }

    @Test
    fun `a thread deleted elsewhere leaves the list without a reload, and the chat is told`() = runTest {
        val topic = MutableSharedFlow<MercureEvent>(extraBufferCapacity = 4)
        every { mercureService.subscribe("/contexts/user-1") } returns topic
        loadedViewModel()
        advanceUntilIdle()
        clearMocks(contextRepository, answers = false, recordedCalls = true)
        val events = mutableListOf<ThreadEvent>()
        backgroundScope.launch { viewModel.threadEvents.toList(events) }
        runCurrent()

        topic.emit(MercureEvent(data = """{"id":"ctx-2","deleted":true}"""))
        runCurrent()

        assertEquals(listOf("ctx-1", "ctx-3"), viewModel.uiState.value.contexts.map { it.id })
        assertEquals(listOf<ThreadEvent>(ThreadEvent.Deleted("ctx-2")), events)
        // Not refreshed: the payload says all there is to know, and a refresh would race the removal.
        coVerify(exactly = 0) { contextRepository.getContexts(any()) }
    }

    @Test
    fun `a thread deleted elsewhere while its undo is on screen ends the undo`() = runTest {
        val topic = MutableSharedFlow<MercureEvent>(extraBufferCapacity = 4)
        every { mercureService.subscribe("/contexts/user-1") } returns topic
        loadedViewModel()
        advanceUntilIdle()

        confirmDeletionOf("ctx-2")
        topic.emit(MercureEvent(data = """{"id":"ctx-2","deleted":true}"""))
        runCurrent()
        advanceTimeBy(ContextViewModel.UNDO_WINDOW_MS + 1)
        runCurrent()

        assertNull(viewModel.uiState.value.undoableDeletion)
        coVerify(exactly = 0) { contextRepository.deleteContext(any()) }
    }

    @Test
    fun `any other event on the contexts stream still refreshes the list`() = runTest {
        val topic = MutableSharedFlow<MercureEvent>(extraBufferCapacity = 4)
        every { mercureService.subscribe("/contexts/user-1") } returns topic
        loadedViewModel()
        advanceUntilIdle()
        coEvery { contextRepository.getContexts() } returns Result.success(sampleContexts.take(1))

        topic.emit(MercureEvent(data = """{"id":"ctx-9","label":"Autre","status":"active"}"""))
        advanceUntilIdle()

        assertEquals(listOf("ctx-1"), viewModel.uiState.value.contexts.map { it.id })
    }
}

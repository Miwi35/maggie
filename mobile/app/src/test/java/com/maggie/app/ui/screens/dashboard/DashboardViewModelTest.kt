package com.maggie.app.ui.screens.dashboard

import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureEvent
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.mercure.MercureTopics
import com.maggie.app.data.repository.AgendaRepository
import com.maggie.app.data.repository.EventRepository
import com.maggie.app.data.repository.TaskRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
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
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var eventRepository: EventRepository
    private lateinit var taskRepository: TaskRepository
    private lateinit var agendaRepository: AgendaRepository
    private lateinit var mercureService: MercureService
    private lateinit var authRepository: AuthRepository
    private lateinit var topics: Map<String, MutableSharedFlow<MercureEvent>>

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        eventRepository = mockk()
        taskRepository = mockk()
        agendaRepository = mockk()
        mercureService = mockk()
        authRepository = mockk(relaxed = true)
        every { authRepository.token } returns flowOf("test-jwt")
        coEvery { authRepository.getUserId() } returns "u1"
        coEvery { agendaRepository.getAgendas() } returns emptyList()
        coEvery { eventRepository.refreshEvents() } returns Result.success(emptyList())
        coEvery { eventRepository.getRecurringBefore(any()) } returns emptyList()
        coEvery { taskRepository.getUndoneTasks(any()) } returns emptyList()
        coEvery { taskRepository.getUndoneUndatedTasks() } returns emptyList()
        topics = listOf(MercureTopics.EVENTS, MercureTopics.TASKS, MercureTopics.AGENDAS).associateWith { collection ->
            MutableSharedFlow<MercureEvent>().also {
                every { mercureService.subscribe(MercureTopics.userScoped("u1", collection)) } returns it
            }
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel() = DashboardViewModel(
        eventRepository,
        taskRepository,
        agendaRepository,
        mercureService,
        authRepository,
    )

    @Test
    fun `loads once on opening and stays quiet without interaction`() = runTest {
        val viewModel = createViewModel()
        advanceTimeBy(10_000)

        coVerify(exactly = 1) { eventRepository.refreshEvents() }
        coVerify(exactly = 1) { taskRepository.getUndoneTasks(any()) }
        assertFalse(viewModel.uiState.value.isLoading)
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `a failing load sets the error`() = runTest {
        coEvery { eventRepository.refreshEvents() } returns Result.failure(RuntimeException("Network error"))

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals("Network error", viewModel.uiState.value.error)
        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test
    fun `ten Mercure updates within 200 ms reload once`() = runTest {
        createViewModel()
        advanceUntilIdle()

        repeat(10) {
            topics.getValue(MercureTopics.EVENTS).emit(MercureEvent())
            advanceTimeBy(20)
        }
        advanceTimeBy(10_000)

        coVerify(exactly = 2) { eventRepository.refreshEvents() }
    }

    @Test
    fun `one change announced on events, tasks and agendas reloads once`() = runTest {
        createViewModel()
        advanceUntilIdle()

        topics.values.forEach { it.emit(MercureEvent()) }
        advanceTimeBy(10_000)

        coVerify(exactly = 2) { eventRepository.refreshEvents() }
    }

    @Test
    fun `toggling a task reloads the lists`() = runTest {
        coEvery { taskRepository.toggleDone("t1", true) } returns Result.success(mockk())
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.toggleTaskDone("t1", true)
        advanceUntilIdle()

        coVerify(exactly = 2) { eventRepository.refreshEvents() }
    }
}

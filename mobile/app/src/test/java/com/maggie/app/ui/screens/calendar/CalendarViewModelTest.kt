package com.maggie.app.ui.screens.calendar

import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.model.Agenda
import com.maggie.app.data.model.Event
import com.maggie.app.data.model.Task
import com.maggie.app.data.repository.AgendaRepository
import com.maggie.app.data.repository.EventRepository
import com.maggie.app.data.repository.TaskRepository
import com.maggie.app.ui.screens.fullcalendar.FullCalendarViewModel
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CalendarViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var eventRepository: EventRepository
    private lateinit var taskRepository: TaskRepository
    private lateinit var agendaRepository: AgendaRepository
    private lateinit var mercureService: MercureService
    private lateinit var authRepository: AuthRepository

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        eventRepository = mockk()
        taskRepository = mockk()
        agendaRepository = mockk()
        mercureService = mockk()
        authRepository = mockk(relaxed = true)
        every { authRepository.token } returns flowOf("test-jwt")
        every { mercureService.subscribe(any()) } returns emptyFlow()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun stubRepositories(
        events: List<Event> = emptyList(),
        tasks: List<Task> = emptyList(),
        agendas: List<Agenda> = emptyList(),
    ) {
        coEvery { agendaRepository.refreshAgendas() } returns Result.success(agendas)
        coEvery { eventRepository.refreshEvents() } returns Result.success(events)
        coEvery { eventRepository.getRecurringBefore(any()) } returns emptyList()
        coEvery { taskRepository.refreshTasks() } returns Result.success(tasks)
        coEvery { taskRepository.getUndoneTasks(any()) } returns tasks
    }

    @Test
    fun `refresh loads agendas and events`() = runTest {
        val agendas = listOf(
            Agenda(id = "a1", name = "Work", color = "#FF0000"),
        )
        val events = listOf(
            Event(id = "1", summary = "Meeting", startAt = "2026-03-01T10:00:00Z", endAt = "2026-03-01T11:00:00Z"),
        )
        stubRepositories(events = events, agendas = agendas)

        val viewModel = FullCalendarViewModel(eventRepository, taskRepository, agendaRepository, mercureService, authRepository)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertNull(state.error)
        assertEquals(1, state.agendas.size)
        assertEquals("Work", state.agendas[0].name)
    }

    @Test
    fun `refresh failure sets error`() = runTest {
        coEvery { agendaRepository.refreshAgendas() } throws RuntimeException("Network error")
        every { mercureService.subscribe(any()) } returns emptyFlow()

        val viewModel = FullCalendarViewModel(eventRepository, taskRepository, agendaRepository, mercureService, authRepository)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals("Network error", state.error)
    }

    @Test
    fun `navigateToDate updates currentDate`() = runTest {
        stubRepositories()

        val viewModel = FullCalendarViewModel(eventRepository, taskRepository, agendaRepository, mercureService, authRepository)
        advanceUntilIdle()

        val target = java.time.LocalDate.of(2026, 6, 15)
        viewModel.navigateToDate(target)

        assertEquals(target, viewModel.uiState.value.currentDate)
    }
}

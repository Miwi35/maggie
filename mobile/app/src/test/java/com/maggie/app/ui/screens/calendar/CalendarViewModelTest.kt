package com.maggie.app.ui.screens.calendar

import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.model.Event
import com.maggie.app.data.repository.EventRepository
import com.maggie.app.data.repository.TaskRepository
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
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class CalendarViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var repository: EventRepository
    private lateinit var taskRepository: TaskRepository
    private lateinit var mercureService: MercureService

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        repository = mockk()
        taskRepository = mockk()
        mercureService = mockk()
        every { mercureService.subscribe(any()) } returns emptyFlow()
        every { taskRepository.observeTasks() } returns flowOf(emptyList())
        coEvery { taskRepository.refreshTasks() } returns Result.success(emptyList())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `loadEvents success populates events and eventsByDate`() = runTest {
        val events = listOf(
            Event(id = "1", summary = "Meeting", startAt = "2026-03-01T10:00:00Z", endAt = "2026-03-01T11:00:00Z"),
            Event(id = "2", summary = "Lunch", startAt = "2026-03-01T12:00:00Z", endAt = "2026-03-01T13:00:00Z"),
            Event(id = "3", summary = "Dinner", startAt = "2026-03-02T19:00:00Z", endAt = "2026-03-02T20:00:00Z"),
        )
        every { repository.observeEvents() } returns flowOf(events)
        coEvery { repository.refreshEvents() } returns Result.success(events)

        val viewModel = CalendarViewModel(repository, taskRepository, mercureService)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(3, state.events.size)
        assertEquals("Meeting", state.events[0].summary)
        assertFalse(state.isLoading)
        assertNull(state.error)

        // eventsByDate should group by local date in event timezone (Europe/Paris = UTC+1 in March)
        assertTrue(state.eventsByDate.isNotEmpty())
        val march1 = LocalDate.of(2026, 3, 1)
        val march2 = LocalDate.of(2026, 3, 2)
        assertEquals(2, state.eventsByDate[march1]?.size)
        assertEquals(1, state.eventsByDate[march2]?.size)
    }

    @Test
    fun `loadEvents failure sets error`() = runTest {
        every { repository.observeEvents() } returns flowOf(emptyList())
        coEvery { repository.refreshEvents() } returns Result.failure(RuntimeException("Network error"))

        val viewModel = CalendarViewModel(repository, taskRepository, mercureService)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.events.isEmpty())
        assertFalse(state.isLoading)
        assertEquals("Network error", state.error)
    }

    @Test
    fun `selectDate updates selectedDate in state`() = runTest {
        every { repository.observeEvents() } returns flowOf(emptyList())
        coEvery { repository.refreshEvents() } returns Result.success(emptyList())

        val viewModel = CalendarViewModel(repository, taskRepository, mercureService)
        advanceUntilIdle()

        val targetDate = LocalDate.of(2026, 6, 15)
        viewModel.selectDate(targetDate)

        assertEquals(targetDate, viewModel.uiState.value.selectedDate)
    }
}

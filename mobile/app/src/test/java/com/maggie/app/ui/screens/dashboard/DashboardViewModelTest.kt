package com.maggie.app.ui.screens.dashboard

import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureEvent
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.mercure.MercureTopics
import com.maggie.app.data.model.Agenda
import com.maggie.app.data.model.DayRoutine
import com.maggie.app.data.model.Event
import com.maggie.app.data.model.RoutineDayView
import com.maggie.app.data.model.RoutineMoment
import com.maggie.app.data.model.Task
import com.maggie.app.data.repository.AgendaRepository
import com.maggie.app.data.repository.EventRepository
import com.maggie.app.data.repository.RoutineRepository
import com.maggie.app.data.repository.TaskRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
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
class DashboardViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var eventRepository: EventRepository
    private lateinit var taskRepository: TaskRepository
    private lateinit var agendaRepository: AgendaRepository
    private lateinit var routineRepository: RoutineRepository
    private lateinit var mercureService: MercureService
    private lateinit var authRepository: AuthRepository
    private val cachedEvents = MutableStateFlow<List<Event>>(emptyList())
    private val cachedAgendas = MutableStateFlow<List<Agenda>>(emptyList())
    private lateinit var topics: Map<String, MutableSharedFlow<MercureEvent>>

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        eventRepository = mockk()
        taskRepository = mockk()
        agendaRepository = mockk()
        routineRepository = mockk()
        coEvery { routineRepository.getDay(any()) } returns Result.success(RoutineDayView(date = "${com.maggie.app.util.DateRanges.todayDate()}"))
        mercureService = mockk()
        authRepository = mockk(relaxed = true)
        every { authRepository.token } returns flowOf("test-jwt")
        coEvery { authRepository.getUserId() } returns "u1"
        coEvery { agendaRepository.loadAgendas(any()) } returns Result.success(emptyList())
        every { agendaRepository.observeAgendas() } returns cachedAgendas
        coEvery { eventRepository.loadPeriod(any(), any()) } returns Result.success(Unit)
        every { eventRepository.observeEvents() } returns cachedEvents
        coEvery { taskRepository.getUndoneTasks(any()) } returns emptyList()
        coEvery { taskRepository.getTodayTasks() } returns emptyList()
        topics = listOf(MercureTopics.TASKS, MercureTopics.ROUTINES, MercureTopics.ROUTINE_CHECKS).associateWith { collection ->
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
        routineRepository,
        mercureService,
        authRepository,
    )

    @Test
    fun `loads once on opening and stays quiet without interaction`() = runTest {
        val viewModel = createViewModel()
        advanceTimeBy(10_000)

        coVerify(exactly = 1) { eventRepository.loadPeriod(any(), any()) }
        coVerify(exactly = 1) { taskRepository.getUndoneTasks(any()) }
        assertFalse(viewModel.uiState.value.isLoading)
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `a failing load sets the error`() = runTest {
        coEvery { eventRepository.loadPeriod(any(), any()) } returns Result.failure(RuntimeException("Network error"))

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals("Network error", viewModel.uiState.value.error)
        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test
    fun `ten task updates within 200 ms reload once, then once more for the index, and never read the period again`() = runTest {
        createViewModel()
        advanceUntilIdle()

        repeat(10) {
            topics.getValue(MercureTopics.TASKS).emit(MercureEvent())
            advanceTimeBy(20)
        }
        advanceTimeBy(600)
        coVerify(exactly = 2) { taskRepository.getUndoneTasks(any()) }

        advanceTimeBy(10_000)
        coVerify(exactly = 3) { taskRepository.getUndoneTasks(any()) }
        // Not forced: the cache answers while the period is fresh.
        coVerify(exactly = 0) { eventRepository.loadPeriod(any(), true) }
    }

    @Test
    fun `an event pushed into the cache shows on the dashboard without a read`() = runTest {
        val today = com.maggie.app.util.DateRanges.todayDate()
        val viewModel = createViewModel()
        advanceUntilIdle()
        assertEquals(emptyList<String>(), viewModel.uiState.value.todayEvents.map { it.id })

        cachedEvents.value = listOf(Event(id = "new", summary = "Anniversaire", allDay = true, startDate = "$today", endDate = "${today.plusDays(1)}"))
        advanceUntilIdle()

        assertEquals(listOf("new"), viewModel.uiState.value.todayEvents.map { it.id })
        coVerify(exactly = 1) { eventRepository.loadPeriod(any(), any()) }
    }

    @Test
    fun `pull-to-refresh reads the period again whatever the cache holds`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.refresh()
        advanceUntilIdle()

        coVerify(exactly = 1) { eventRepository.loadPeriod(any(), true) }
    }

    @Test
    fun `refreshes asked while a load is running fold into one follow-up load`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        coEvery { agendaRepository.loadAgendas(any()) } coAnswers {
            if (calls++ == 0) gate.await()
            Result.success(emptyList())
        }

        val viewModel = createViewModel()
        runCurrent()
        repeat(5) { viewModel.refresh() }
        gate.complete(Unit)
        advanceUntilIdle()

        coVerify(exactly = 2) { agendaRepository.loadAgendas(any()) }
    }

    /** MAG-382: today's all-day event is today's, and yesterday's does not come back as today's at 01:00. */
    @Test
    fun `an all-day event is listed on its date, not the day after`() = runTest {
        val today = com.maggie.app.util.DateRanges.todayDate()
        cachedEvents.value =
            listOf(
                Event(id = "today", summary = "Anniversaire", allDay = true, startDate = "$today", endDate = "${today.plusDays(1)}"),
                Event(id = "yesterday", summary = "Veille", allDay = true, startDate = "${today.minusDays(1)}", endDate = "$today"),
                Event(id = "in3", summary = "Plus tard", allDay = true, startDate = "${today.plusDays(3)}", endDate = "${today.plusDays(4)}"),
            )

        val viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf("today"), state.todayEvents.map { it.id })
        assertEquals(emptyList<String>(), state.tomorrowEvents.map { it.id })
        assertEquals(listOf("in3"), state.weekEvents.map { it.id })
        assertEquals(emptyList<String>(), state.monthEvents.map { it.id })
    }

    /** MAG-404: a due date is a day, compared with today's date and never shifted by a zone. */
    @Test
    fun `a task is listed on its due day`() = runTest {
        val today = com.maggie.app.util.DateRanges.todayDate()
        coEvery { taskRepository.getUndoneTasks(any()) } returns listOf(
            Task(id = "tomorrow", title = "Demain", dueDate = "${today.plusDays(1)}"),
            Task(id = "in3", title = "Plus tard", dueDate = "${today.plusDays(3)}"),
            Task(id = "in10", title = "Bien plus tard", dueDate = "${today.plusDays(10)}"),
        )

        val viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf("tomorrow"), state.tomorrowTasks.map { it.id })
        assertEquals(listOf("in3"), state.weekTasks.map { it.id })
        assertEquals(listOf("in10"), state.monthTasks.map { it.id })
        coVerify { taskRepository.getUndoneTasks("${today.plusMonths(1)}") }
    }

    /** MAG-405: « Aujourd'hui » is the API's view as it comes — the order, the lateness, the subtasks under their task. */
    @Test
    fun `today is the API view, in its order, with its lateness`() = runTest {
        val today = com.maggie.app.util.DateRanges.todayDate()
        coEvery { taskRepository.getTodayTasks() } returns listOf(
            Task(id = "late", title = "En retard", dueDate = "${today.minusDays(2)}", overdueDays = 2),
            Task(id = "parent", title = "Préparer le déménagement", dueDate = "$today"),
            Task(id = "sub", title = "Réserver le camion", parent = "/api/tasks/parent"),
        )
        // The month read also holds them; the today list does not come from it.
        coEvery { taskRepository.getUndoneTasks(any()) } returns listOf(
            Task(id = "late", title = "En retard", dueDate = "${today.minusDays(2)}"),
            Task(id = "parent", title = "Préparer le déménagement", dueDate = "$today"),
        )

        val viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf("late", "parent", "sub"), state.todayTasks.map { it.id })
        assertEquals(listOf(2, null, null), state.todayTasks.map { it.daysLate })
        assertEquals(emptyList<String>(), state.tomorrowTasks.map { it.id })
    }

    /** MAG-406: the other days go by the API's order score, the closest due day at equal score. */
    @Test
    fun `the other days list their tasks by score, then by due day`() = runTest {
        val today = com.maggie.app.util.DateRanges.todayDate()
        coEvery { taskRepository.getUndoneTasks(any()) } returns listOf(
            Task(id = "in5-low", title = "Basse", dueDate = "${today.plusDays(5)}", urgency = "low", orderScore = 15),
            Task(id = "in6-critical", title = "Critique", dueDate = "${today.plusDays(6)}", urgency = "high", orderScore = 51),
            Task(id = "in4-low", title = "Basse plus tôt", dueDate = "${today.plusDays(4)}", urgency = "low", orderScore = 15),
        )

        val viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf("in6-critical", "in4-low", "in5-low"), state.weekTasks.map { it.id })
        assertEquals(listOf("high", "low", "low"), state.weekTasks.map { it.urgency })
    }

    /** MAG-406: « Aujourd'hui » keeps the API's score order, whatever the ids or the criticalities say. */
    @Test
    fun `today keeps the API score order and carries the urgency`() = runTest {
        val today = com.maggie.app.util.DateRanges.todayDate()
        coEvery { taskRepository.getTodayTasks() } returns listOf(
            Task(id = "c", title = "Payer la cantine", criticality = "high", dueDate = "$today", urgency = "high", orderScore = 78),
            Task(id = "b", title = "Rendre le livre", dueDate = "${today.minusDays(1)}", overdueDays = 1, urgency = "high", orderScore = 67),
            Task(id = "a", title = "Arroser", dueDate = "$today", urgency = "normal", orderScore = 55),
        )

        val viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf("c", "b", "a"), state.todayTasks.map { it.id })
        assertEquals(listOf("high", "high", "normal"), state.todayTasks.map { it.urgency })
    }

    @Test
    fun `a task that is done carries no lateness`() {
        assertEquals(null, Task(id = "t", title = "Fait", completedAt = "2026-10-13T08:00:00Z", overdueDays = 2).daysLate)
        assertEquals(null, Task(id = "t", title = "À l'heure", overdueDays = null).daysLate)
        assertEquals(1, Task(id = "t", title = "Hier", overdueDays = 1).daysLate)
    }

    /** MAG-405: loading, then the list; an unreadable view is an error, not an empty day. */
    @Test
    fun `the today view goes from loading to the list, or to an error`() = runTest {
        val gate = CompletableDeferred<List<Task>>()
        coEvery { taskRepository.getTodayTasks() } coAnswers { gate.await() }

        val viewModel = createViewModel()
        runCurrent()
        assertEquals(true, viewModel.uiState.value.isLoading)
        assertEquals(emptyList<String>(), viewModel.uiState.value.todayTasks.map { it.id })

        gate.complete(listOf(Task(id = "late", title = "En retard", overdueDays = 1)))
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isLoading)
        assertEquals(listOf("late"), viewModel.uiState.value.todayTasks.map { it.id })

        coEvery { taskRepository.getTodayTasks() } throws RuntimeException("Tâches du jour : 500")
        viewModel.refresh()
        advanceUntilIdle()
        assertEquals("Tâches du jour : 500", viewModel.uiState.value.error)
        assertFalse(viewModel.uiState.value.isLoading)
    }

    /** MAG-404: on the other days an undated subtask is read in its parent's detail; a dated one keeps its row. */
    @Test
    fun `an undated subtask is not a row of its own on the other days, a dated one is`() = runTest {
        val today = com.maggie.app.util.DateRanges.todayDate()
        val tomorrow = today.plusDays(1)
        coEvery { taskRepository.getUndoneTasks(any()) } returns listOf(
            Task(id = "parent", title = "Préparer le déménagement", dueDate = "$tomorrow"),
            Task(id = "dated", title = "Résilier la box", dueDate = "$tomorrow", parent = "/api/tasks/parent"),
            Task(id = "undated", title = "Réserver le camion", parent = "/api/tasks/parent"),
        )

        val viewModel = createViewModel()
        advanceUntilIdle()

        // Which rows, not their order: at equal score the ids decide (MAG-406).
        assertEquals(setOf("parent", "dated"), viewModel.uiState.value.tomorrowTasks.map { it.id }.toSet())
        assertEquals(2, viewModel.uiState.value.tomorrowTasks.size)
    }

    @Test
    fun `toggling a task reloads the lists`() = runTest {
        coEvery { taskRepository.toggleDone("t1", true) } returns Result.success(mockk())
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.toggleTaskDone("t1", true)
        advanceUntilIdle()

        coVerify(exactly = 2) { taskRepository.getUndoneTasks(any()) }
        coVerify(exactly = 0) { eventRepository.loadPeriod(any(), true) }
    }

    private fun routinesToday(vararg checked: Boolean) = RoutineDayView(
        date = "${com.maggie.app.util.DateRanges.todayDate()}",
        moments = listOf(
            RoutineMoment(
                anchor = "wake",
                time = "07:30",
                routines = checked.mapIndexed { i, done -> DayRoutine("r$i", "Routine $i", "wake", time = "07:30", checked = done) },
            ),
        ),
    )

    @Test
    fun `the routines due today are loaded for the widget (MAG-512)`() = runTest {
        coEvery { routineRepository.getDay(any()) } returns Result.success(routinesToday(true, false, false))

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals("1 sur 3 aujourd'hui", viewModel.uiState.value.routines?.summary)
    }

    @Test
    fun `unreadable routines leave the dashboard loaded without a widget (MAG-512)`() = runTest {
        coEvery { routineRepository.getDay(any()) } returns Result.failure(RuntimeException("offline"))

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.routines)
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `ticking a routine of the widget ticks it at once and sends the tick (MAG-512)`() = runTest {
        coEvery { routineRepository.getDay(any()) } returns Result.success(routinesToday(false, false))
        coEvery { routineRepository.setChecked(any(), any(), any()) } returns Result.success(mockk(relaxed = true))
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.toggleRoutine(viewModel.uiState.value.routines!!.routines.first())
        runCurrent()
        assertEquals("1 sur 2 aujourd'hui", viewModel.uiState.value.routines?.summary)
        advanceUntilIdle()

        coVerify(exactly = 1) { routineRepository.setChecked("r0", com.maggie.app.util.DateRanges.todayDate(), true) }
    }

    @Test
    fun `a refused tick of the widget is taken back (MAG-512)`() = runTest {
        coEvery { routineRepository.getDay(any()) } returns Result.success(routinesToday(false))
        coEvery { routineRepository.setChecked(any(), any(), any()) } returns Result.failure(RuntimeException("refused"))
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.toggleRoutine(viewModel.uiState.value.routines!!.routines.first())
        advanceUntilIdle()

        assertEquals("0 sur 1 aujourd'hui", viewModel.uiState.value.routines?.summary)
    }

    @Test
    fun `a routine ticked elsewhere refreshes the widget without reading the tasks again (MAG-512)`() = runTest {
        coEvery { routineRepository.getDay(any()) } returns Result.success(routinesToday(false, false))
        val viewModel = createViewModel()
        advanceUntilIdle()

        coEvery { routineRepository.getDay(any()) } returns Result.success(routinesToday(true, true))
        topics.getValue(MercureTopics.ROUTINE_CHECKS).emit(MercureEvent())
        advanceUntilIdle()

        assertEquals("2 sur 2 aujourd'hui", viewModel.uiState.value.routines?.summary)
        assertTrue(viewModel.uiState.value.routines!!.routines.all { it.checked })
        coVerify(exactly = 1) { taskRepository.getUndoneTasks(any()) }
    }

    @Test
    fun `a routine created or changed refreshes the widget (MAG-512)`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()
        assertEquals(0, viewModel.uiState.value.routines?.dueCount)

        coEvery { routineRepository.getDay(any()) } returns Result.success(routinesToday(false))
        topics.getValue(MercureTopics.ROUTINES).emit(MercureEvent())
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.routines?.dueCount)
    }
}

package com.maggie.app.ui.screens.calendar

import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureEvent
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.mercure.MercureTopics
import com.maggie.app.data.model.Agenda
import com.maggie.app.data.model.Event
import com.maggie.app.data.model.Task
import com.maggie.app.data.model.UserPreference
import com.maggie.app.data.repository.AgendaRepository
import com.maggie.app.data.repository.EventRepository
import com.maggie.app.data.repository.TaskRepository
import com.maggie.app.data.repository.UserPreferenceRepository
import com.maggie.app.ui.screens.fullcalendar.CalendarViewType
import com.maggie.app.ui.screens.fullcalendar.FullCalendarViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
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
    private lateinit var userPreferenceRepository: UserPreferenceRepository
    private lateinit var preference: MutableStateFlow<UserPreference?>

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
        preference = MutableStateFlow(null)
        userPreferenceRepository = mockk()
        every { userPreferenceRepository.preference } returns preference
        coEvery { userPreferenceRepository.refresh() } returns Result.failure(RuntimeException("offline"))
    }

    private fun createViewModel() = FullCalendarViewModel(
        eventRepository,
        taskRepository,
        agendaRepository,
        mercureService,
        authRepository,
        userPreferenceRepository,
    )

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

        val viewModel = createViewModel()
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

        val viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals("Network error", state.error)
    }

    @Test
    fun `navigateToDate updates currentDate`() = runTest {
        stubRepositories()

        val viewModel = createViewModel()
        advanceUntilIdle()

        val target = java.time.LocalDate.of(2026, 6, 15)
        viewModel.navigateToDate(target)

        assertEquals(target, viewModel.uiState.value.currentDate)
    }

    @Test
    fun `subscribes to Mercure once the user has logged in`() = runTest {
        stubRepositories()
        val token = MutableStateFlow<String?>(null)
        every { authRepository.token } returns token
        coEvery { authRepository.getUserId() } returns null

        createViewModel()
        advanceUntilIdle()
        verify(exactly = 0) { mercureService.subscribe(any()) }

        coEvery { authRepository.getUserId() } returns "u1"
        token.value = "jwt"
        advanceUntilIdle()

        listOf(MercureTopics.EVENTS, MercureTopics.TASKS, MercureTopics.AGENDAS).forEach { collection ->
            verify(exactly = 1) { mercureService.subscribe(MercureTopics.userScoped("u1", collection)) }
        }
    }

    private fun mercureTopics(): Map<String, MutableSharedFlow<MercureEvent>> {
        coEvery { authRepository.getUserId() } returns "u1"
        return listOf(MercureTopics.EVENTS, MercureTopics.TASKS, MercureTopics.AGENDAS).associateWith { collection ->
            MutableSharedFlow<MercureEvent>().also {
                every { mercureService.subscribe(MercureTopics.userScoped("u1", collection)) } returns it
            }
        }
    }

    @Test
    fun `an agenda left open without interaction loads each list once`() = runTest {
        stubRepositories()
        mercureTopics()

        createViewModel()
        advanceTimeBy(10_000)

        coVerify(exactly = 1) { eventRepository.refreshEvents() }
        coVerify(exactly = 1) { taskRepository.refreshTasks() }
        coVerify(exactly = 1) { agendaRepository.refreshAgendas() }
    }

    @Test
    fun `a Mercure update reloads after the burst window, then once more for the index`() = runTest {
        stubRepositories()
        val topics = mercureTopics()

        createViewModel()
        advanceUntilIdle()
        coVerify(exactly = 1) { eventRepository.refreshEvents() }

        topics.getValue(MercureTopics.EVENTS).emit(MercureEvent())
        advanceTimeBy(100)
        coVerify(exactly = 1) { eventRepository.refreshEvents() }

        advanceTimeBy(500)
        coVerify(exactly = 2) { eventRepository.refreshEvents() }

        advanceTimeBy(10_000)
        coVerify(exactly = 3) { eventRepository.refreshEvents() }
    }

    @Test
    fun `ten Mercure updates within 200 ms reload once, then once more for the index`() = runTest {
        stubRepositories()
        val topics = mercureTopics()

        createViewModel()
        advanceUntilIdle()

        repeat(10) {
            topics.getValue(MercureTopics.EVENTS).emit(MercureEvent())
            advanceTimeBy(20)
        }
        advanceTimeBy(600)
        coVerify(exactly = 2) { eventRepository.refreshEvents() }
        coVerify(exactly = 2) { taskRepository.refreshTasks() }
        coVerify(exactly = 2) { agendaRepository.refreshAgendas() }

        advanceTimeBy(10_000)
        coVerify(exactly = 3) { eventRepository.refreshEvents() }
    }

    @Test
    fun `one change announced on events, tasks and agendas reloads once`() = runTest {
        stubRepositories()
        val topics = mercureTopics()

        createViewModel()
        advanceUntilIdle()

        topics.values.forEach { it.emit(MercureEvent()) }
        advanceTimeBy(600)
        coVerify(exactly = 2) { eventRepository.refreshEvents() }

        advanceTimeBy(10_000)
        coVerify(exactly = 3) { eventRepository.refreshEvents() }
    }

    @Test
    fun `a new burst replaces the pending index re-check`() = runTest {
        stubRepositories()
        val topics = mercureTopics()

        createViewModel()
        advanceUntilIdle()

        topics.getValue(MercureTopics.EVENTS).emit(MercureEvent())
        advanceTimeBy(1_000)
        topics.getValue(MercureTopics.EVENTS).emit(MercureEvent())
        advanceTimeBy(10_000)

        coVerify(exactly = 4) { eventRepository.refreshEvents() }
    }

    @Test
    fun `updates a second apart each reload`() = runTest {
        stubRepositories()
        val topics = mercureTopics()

        createViewModel()
        advanceUntilIdle()

        topics.getValue(MercureTopics.TASKS).emit(MercureEvent())
        advanceTimeBy(1_000)
        topics.getValue(MercureTopics.TASKS).emit(MercureEvent())
        advanceTimeBy(1_000)

        coVerify(exactly = 3) { taskRepository.refreshTasks() }
    }

    @Test
    fun `refreshes asked while a load is running fold into one follow-up load`() = runTest {
        stubRepositories()
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        coEvery { agendaRepository.refreshAgendas() } coAnswers {
            if (calls++ == 0) gate.await()
            Result.success(emptyList())
        }

        val viewModel = createViewModel()
        runCurrent()
        repeat(5) { viewModel.refresh() }
        gate.complete(Unit)
        advanceUntilIdle()

        coVerify(exactly = 2) { agendaRepository.refreshAgendas() }
    }

    private val work = Agenda(id = "01WORK", name = "Work", color = "#FF0000")
    private val home = Agenda(id = "01HOME", name = "Home", color = "#00FF00")

    private fun preferenceOf(view: String = "month", agendaIds: List<String> = emptyList()) =
        UserPreference(defaultCalendarView = view, enabledAgendaIds = agendaIds)

    @Test
    fun `starts on the month view and every agenda when no preference was saved`() = runTest {
        stubRepositories(agendas = listOf(work, home))
        preference.value = preferenceOf()

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals(CalendarViewType.MONTH, viewModel.uiState.value.viewType)
        assertEquals(setOf("01WORK", "01HOME"), viewModel.uiState.value.enabledAgendas)
    }

    @Test
    fun `default view week is applied as the initial view`() = runTest {
        stubRepositories()
        preference.value = preferenceOf(view = "week")

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals(CalendarViewType.WEEK, viewModel.uiState.value.viewType)
    }

    @Test
    fun `default view day is applied as the initial view`() = runTest {
        stubRepositories()
        preference.value = preferenceOf(view = "day")

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals(CalendarViewType.DAY, viewModel.uiState.value.viewType)
    }

    @Test
    fun `default view month is applied as the initial view`() = runTest {
        stubRepositories()
        preference.value = preferenceOf(view = "month")

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals(CalendarViewType.MONTH, viewModel.uiState.value.viewType)
    }

    @Test
    fun `an unknown default view falls back to month`() = runTest {
        stubRepositories()
        preference.value = preferenceOf(view = "agenda")

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals(CalendarViewType.MONTH, viewModel.uiState.value.viewType)
    }

    @Test
    fun `enabled agenda ids saved as IRIs filter the initial agendas`() = runTest {
        stubRepositories(agendas = listOf(work, home))
        preference.value = preferenceOf(agendaIds = listOf("/api/agendas/01HOME"))

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals(setOf("01HOME"), viewModel.uiState.value.enabledAgendas)
    }

    @Test
    fun `enabled agenda ids saved as bare ids filter the initial agendas`() = runTest {
        stubRepositories(agendas = listOf(work, home))
        preference.value = preferenceOf(agendaIds = listOf("01WORK"))

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals(setOf("01WORK"), viewModel.uiState.value.enabledAgendas)
    }

    @Test
    fun `an empty list of enabled agendas means every agenda`() = runTest {
        stubRepositories(agendas = listOf(work, home))
        preference.value = preferenceOf(agendaIds = emptyList())

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals(setOf("01WORK", "01HOME"), viewModel.uiState.value.enabledAgendas)
    }

    @Test
    fun `enabled agenda ids matching no agenda fall back to every agenda`() = runTest {
        stubRepositories(agendas = listOf(work, home))
        preference.value = preferenceOf(agendaIds = listOf("/api/agendas/01GONE"))

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals(setOf("01WORK", "01HOME"), viewModel.uiState.value.enabledAgendas)
    }

    @Test
    fun `preferences arriving after the agendas narrow the initial state`() = runTest {
        stubRepositories(agendas = listOf(work, home))
        val viewModel = createViewModel()
        advanceUntilIdle()
        assertEquals(setOf("01WORK", "01HOME"), viewModel.uiState.value.enabledAgendas)

        preference.value = preferenceOf(view = "day", agendaIds = listOf("01WORK"))
        advanceUntilIdle()

        assertEquals(CalendarViewType.DAY, viewModel.uiState.value.viewType)
        assertEquals(setOf("01WORK"), viewModel.uiState.value.enabledAgendas)
    }

    @Test
    fun `agendas arriving after the preferences are filtered the same way`() = runTest {
        val agendas = CompletableDeferred<Result<List<Agenda>>>()
        stubRepositories()
        coEvery { agendaRepository.refreshAgendas() } coAnswers { agendas.await() }
        preference.value = preferenceOf(agendaIds = listOf("01HOME"))

        val viewModel = createViewModel()
        advanceUntilIdle()
        agendas.complete(Result.success(listOf(work, home)))
        advanceUntilIdle()

        assertEquals(setOf("01HOME"), viewModel.uiState.value.enabledAgendas)
    }

    @Test
    fun `fetches the preferences itself when the repository holds none`() = runTest {
        stubRepositories(agendas = listOf(work, home))
        coEvery { userPreferenceRepository.refresh() } coAnswers {
            val pref = preferenceOf(view = "week", agendaIds = listOf("01WORK"))
            preference.value = pref
            Result.success(pref)
        }

        val viewModel = createViewModel()
        advanceUntilIdle()

        coVerify(exactly = 1) { userPreferenceRepository.refresh() }
        assertEquals(CalendarViewType.WEEK, viewModel.uiState.value.viewType)
        assertEquals(setOf("01WORK"), viewModel.uiState.value.enabledAgendas)
    }

    @Test
    fun `does not fetch the preferences again when the repository already holds them`() = runTest {
        stubRepositories()
        preference.value = preferenceOf(view = "week")

        createViewModel()
        advanceUntilIdle()

        coVerify(exactly = 0) { userPreferenceRepository.refresh() }
    }

    @Test
    fun `a failing preferences request keeps the defaults`() = runTest {
        stubRepositories(agendas = listOf(work, home))
        coEvery { userPreferenceRepository.refresh() } throws RuntimeException("boom")

        val viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(CalendarViewType.MONTH, state.viewType)
        assertEquals(setOf("01WORK", "01HOME"), state.enabledAgendas)
        assertNull(state.error)
        assertFalse(state.isLoading)
    }

    @Test
    fun `a view chosen before the preferences arrive is kept`() = runTest {
        stubRepositories(agendas = listOf(work, home))
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.setViewType(CalendarViewType.WEEK)
        preference.value = preferenceOf(view = "day")
        advanceUntilIdle()

        assertEquals(CalendarViewType.WEEK, viewModel.uiState.value.viewType)
    }

    @Test
    fun `an agenda toggled before the preferences arrive is kept`() = runTest {
        stubRepositories(agendas = listOf(work, home))
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.toggleAgenda("01HOME")
        preference.value = preferenceOf(agendaIds = listOf("01HOME"))
        advanceUntilIdle()

        assertEquals(setOf("01WORK"), viewModel.uiState.value.enabledAgendas)
    }

    @Test
    fun `preferences saved later do not override the initial state again`() = runTest {
        stubRepositories(agendas = listOf(work, home))
        preference.value = preferenceOf(view = "week", agendaIds = listOf("01WORK"))
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.setViewType(CalendarViewType.DAY)
        viewModel.toggleAgenda("01HOME")
        preference.value = preferenceOf(view = "month", agendaIds = listOf("01HOME"))
        advanceUntilIdle()

        assertEquals(CalendarViewType.DAY, viewModel.uiState.value.viewType)
        assertEquals(setOf("01WORK", "01HOME"), viewModel.uiState.value.enabledAgendas)
    }
}

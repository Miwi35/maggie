package com.maggie.app.ui.screens.calendar

import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureEvent
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.mercure.MercureTopics
import com.maggie.app.data.model.Agenda
import com.maggie.app.data.model.Event
import com.maggie.app.data.model.Meal
import com.maggie.app.data.model.MealSlot
import com.maggie.app.data.model.Proaction
import com.maggie.app.data.model.Task
import com.maggie.app.data.model.UserPreference
import com.maggie.app.data.repository.AgendaRepository
import com.maggie.app.data.repository.EventRepository
import com.maggie.app.data.repository.MealRepository
import com.maggie.app.data.repository.ProactionRepository
import com.maggie.app.data.repository.TaskRepository
import com.maggie.app.data.repository.UserPreferenceRepository
import com.maggie.app.ui.screens.fullcalendar.CalendarViewType
import com.maggie.app.ui.screens.fullcalendar.FullCalendarViewModel
import com.maggie.app.util.DateRanges
import com.maggie.app.util.DayPeriod
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
import kotlinx.coroutines.test.TestScope
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
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class CalendarViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var eventRepository: EventRepository
    private lateinit var taskRepository: TaskRepository
    private lateinit var agendaRepository: AgendaRepository
    private lateinit var mealRepository: MealRepository
    private lateinit var proactionRepository: ProactionRepository
    private lateinit var mercureService: MercureService
    private lateinit var authRepository: AuthRepository
    private lateinit var userPreferenceRepository: UserPreferenceRepository
    private lateinit var preference: MutableStateFlow<UserPreference?>

    // The cache the screen observes: what a read, a push or an edit puts in the tables.
    private val cachedEvents = MutableStateFlow<List<Event>>(emptyList())
    private val cachedAgendas = MutableStateFlow<List<Agenda>>(emptyList())

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        eventRepository = mockk()
        taskRepository = mockk()
        agendaRepository = mockk()
        mealRepository = mockk()
        coEvery { mealRepository.getMeals(any(), any()) } returns Result.success(emptyList())
        proactionRepository = mockk()
        coEvery { proactionRepository.getProactions(any(), any()) } returns Result.success(emptyList())
        every { eventRepository.observeEvents() } returns cachedEvents
        every { agendaRepository.observeAgendas() } returns cachedAgendas
        mercureService = mockk()
        authRepository = mockk(relaxed = true)
        every { authRepository.token } returns flowOf("test-jwt")
        every { mercureService.subscribe(any()) } returns emptyFlow()
        preference = MutableStateFlow(null)
        userPreferenceRepository = mockk()
        every { userPreferenceRepository.preference } returns preference
        coEvery { userPreferenceRepository.refresh() } returns Result.failure(RuntimeException("offline"))
    }

    private fun createViewModel(module: String? = null, clock: Clock = Clock.systemUTC()) = FullCalendarViewModel(
        eventRepository,
        taskRepository,
        agendaRepository,
        mercureService,
        authRepository,
        userPreferenceRepository,
        mealRepository,
        proactionRepository,
        module,
        clock,
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
        cachedEvents.value = events
        cachedAgendas.value = agendas
        coEvery { agendaRepository.loadAgendas(any()) } returns Result.success(agendas)
        coEvery { eventRepository.loadPeriod(any(), any()) } returns Result.success(Unit)
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
        coEvery { agendaRepository.loadAgendas(any()) } returns Result.failure(RuntimeException("Network error"))
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
    fun `subscribes to the tasks, the meals and the proactions once the user has logged in, the events go through the cache feed`() = runTest {
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

        listOf(MercureTopics.TASKS, MercureTopics.MEALS, MercureTopics.PROACTIONS).forEach { collection ->
            verify(exactly = 1) { mercureService.subscribe(MercureTopics.userScoped("u1", collection)) }
        }
        listOf(MercureTopics.EVENTS, MercureTopics.AGENDAS).forEach { collection ->
            verify(exactly = 0) { mercureService.subscribe(MercureTopics.userScoped("u1", collection)) }
        }
    }

    private fun mercureTopics(): Map<String, MutableSharedFlow<MercureEvent>> {
        coEvery { authRepository.getUserId() } returns "u1"
        return listOf(MercureTopics.TASKS, MercureTopics.MEALS, MercureTopics.PROACTIONS).associateWith { collection ->
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

        coVerify(exactly = 1) { eventRepository.loadPeriod(any(), any()) }
        coVerify(exactly = 1) { taskRepository.refreshTasks() }
        coVerify(exactly = 1) { agendaRepository.loadAgendas(any()) }
    }

    @Test
    fun `a task update reloads the tasks after the burst window, then once more, and never the period`() = runTest {
        stubRepositories()
        val topics = mercureTopics()

        createViewModel()
        advanceUntilIdle()
        coVerify(exactly = 1) { taskRepository.refreshTasks() }

        topics.getValue(MercureTopics.TASKS).emit(MercureEvent())
        advanceTimeBy(100)
        coVerify(exactly = 1) { taskRepository.refreshTasks() }

        advanceTimeBy(500)
        coVerify(exactly = 2) { taskRepository.refreshTasks() }

        advanceTimeBy(10_000)
        coVerify(exactly = 3) { taskRepository.refreshTasks() }
        coVerify(exactly = 1) { eventRepository.loadPeriod(any(), any()) }
        coVerify(exactly = 1) { agendaRepository.loadAgendas(any()) }
    }

    @Test
    fun `ten Mercure updates within 200 ms reload once, then once more for the index`() = runTest {
        stubRepositories()
        val topics = mercureTopics()

        createViewModel()
        advanceUntilIdle()

        repeat(10) {
            topics.getValue(MercureTopics.TASKS).emit(MercureEvent())
            advanceTimeBy(20)
        }
        advanceTimeBy(600)
        coVerify(exactly = 2) { taskRepository.refreshTasks() }

        advanceTimeBy(10_000)
        coVerify(exactly = 3) { taskRepository.refreshTasks() }
    }

    @Test
    fun `one change announced on tasks and meals reloads once`() = runTest {
        stubRepositories()
        val topics = mercureTopics()

        createViewModel()
        advanceUntilIdle()

        topics.values.forEach { it.emit(MercureEvent()) }
        advanceTimeBy(600)
        coVerify(exactly = 2) { taskRepository.refreshTasks() }

        advanceTimeBy(10_000)
        coVerify(exactly = 3) { taskRepository.refreshTasks() }
    }

    @Test
    fun `a new burst replaces the pending index re-check`() = runTest {
        stubRepositories()
        val topics = mercureTopics()

        createViewModel()
        advanceUntilIdle()

        topics.getValue(MercureTopics.TASKS).emit(MercureEvent())
        advanceTimeBy(1_000)
        topics.getValue(MercureTopics.TASKS).emit(MercureEvent())
        advanceTimeBy(10_000)

        coVerify(exactly = 4) { taskRepository.refreshTasks() }
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

    private val juneEvents = (1..37).map { n ->
        val day = 1 + (n - 1) % 28
        Event(
            id = "01E%02d".format(n),
            summary = "Rendez-vous $n",
            startAt = "2026-06-%02dT10:00:00Z".format(day),
            endAt = "2026-06-%02dT11:00:00Z".format(day),
        )
    }

    // MAG-430: the month held 37 events and the screen drew the first 30.
    @Test
    fun `a month holding more than a page of events draws every one`() = runTest {
        stubRepositories(events = juneEvents)

        val viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.navigateToDate(java.time.LocalDate.of(2026, 6, 15))
        advanceUntilIdle()

        assertEquals(37, viewModel.uiState.value.expandedEvents.count { it.summary.startsWith("Rendez-vous") })
    }

    @Test
    fun `changing the month asks for the period of that month, grid included`() = runTest {
        stubRepositories()
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.navigateToDate(java.time.LocalDate.of(2026, 6, 15))
        advanceUntilIdle()

        // June 2026 is drawn from Monday 1 June to Sunday 5 July.
        coVerify {
            eventRepository.loadPeriod(
                DayPeriod(java.time.LocalDate.of(2026, 6, 1), java.time.LocalDate.of(2026, 7, 6)),
                false,
            )
        }
    }

    @Test
    fun `the week and the day views ask for their own days`() = runTest {
        stubRepositories()
        val viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.navigateToDate(java.time.LocalDate.of(2026, 6, 17))
        advanceUntilIdle()

        viewModel.setViewType(CalendarViewType.WEEK)
        advanceUntilIdle()
        coVerify {
            eventRepository.loadPeriod(DayPeriod(java.time.LocalDate.of(2026, 6, 15), java.time.LocalDate.of(2026, 6, 22)), false)
        }

        viewModel.setViewType(CalendarViewType.DAY)
        advanceUntilIdle()
        coVerify {
            eventRepository.loadPeriod(DayPeriod(java.time.LocalDate.of(2026, 6, 17), java.time.LocalDate.of(2026, 6, 18)), false)
        }
    }

    @Test
    fun `pull-to-refresh reads the period and the agendas again whatever the cache holds`() = runTest {
        stubRepositories()
        val viewModel = createViewModel()
        advanceUntilIdle()
        coVerify(exactly = 0) { eventRepository.loadPeriod(any(), true) }

        viewModel.refresh()
        advanceUntilIdle()

        coVerify(exactly = 1) { eventRepository.loadPeriod(any(), true) }
        coVerify(exactly = 1) { agendaRepository.loadAgendas(true) }
    }

    @Test
    fun `an event pushed into the cache shows without the period being read again`() = runTest {
        stubRepositories(events = listOf(event))
        val viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.navigateToDate(java.time.LocalDate.of(2026, 6, 15))
        advanceUntilIdle()
        assertEquals(listOf("Dentiste"), viewModel.uiState.value.expandedEvents.map { it.summary })
        val reads = 2

        cachedEvents.value = listOf(event, Event(id = "01NEW", summary = "Coiffeur", startAt = "2026-06-16T08:00:00Z", endAt = "2026-06-16T09:00:00Z"))
        advanceUntilIdle()

        assertEquals(listOf("Dentiste", "Coiffeur"), viewModel.uiState.value.expandedEvents.map { it.summary })
        coVerify(exactly = reads) { eventRepository.loadPeriod(any(), any()) }
    }

    @Test
    fun `an agenda pushed into the cache is listed and enabled`() = runTest {
        stubRepositories(agendas = listOf(work))
        val viewModel = createViewModel()
        advanceUntilIdle()

        cachedAgendas.value = listOf(work, home)
        advanceUntilIdle()

        assertEquals(listOf("Home", "Work"), viewModel.uiState.value.agendas.map { it.name }.sorted())
        assertEquals(setOf("01WORK", "01HOME"), viewModel.uiState.value.enabledAgendas)
    }

    @Test
    fun `refreshes asked while a load is running fold into one follow-up load`() = runTest {
        stubRepositories()
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

    private val dinner = Meal(id = "01MEAL", summary = "Gratin", date = "2026-06-15", slot = MealSlot.DINNER)
    private val event = Event(id = "01RDV", summary = "Dentiste", startAt = "2026-06-15T10:00:00Z", endAt = "2026-06-15T11:00:00Z")

    @Test
    fun `the general calendar draws the meals on their day, under one Repas line`() = runTest {
        stubRepositories(events = listOf(event))
        coEvery { mealRepository.getMeals(any(), any()) } returns Result.success(listOf(dinner))

        val viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.navigateToDate(java.time.LocalDate.of(2026, 6, 15))
        advanceUntilIdle()

        val titles = viewModel.uiState.value.expandedEvents.map { it.summary }
        assertTrue("Dentiste" in titles)
        assertTrue("Dîner: Gratin" in titles)
        assertEquals(listOf("__meals__"), viewModel.uiState.value.expandedEvents.mapNotNull { it.agendaIri }.distinct())
        assertTrue(viewModel.uiState.value.agendas.none { it.name == "Repas" })
    }

    /** MAG-382: a day is its dates. The 14th's event, which midnight UTC used to drag into the 15th, stays on the 14th. */
    @Test
    fun `the day view holds the all-day events of its date only, and the meals as dates`() = runTest {
        val birthday = Event(id = "01BDAY", summary = "Anniversaire", allDay = true, startDate = "2026-06-15", endDate = "2026-06-16")
        val dayBefore = Event(id = "01EVE", summary = "Veille", allDay = true, startDate = "2026-06-14", endDate = "2026-06-15")
        stubRepositories(events = listOf(birthday, dayBefore))
        coEvery { mealRepository.getMeals(any(), any()) } returns Result.success(listOf(dinner))

        val viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.setViewType(com.maggie.app.ui.screens.fullcalendar.CalendarViewType.DAY)
        viewModel.navigateToDate(java.time.LocalDate.of(2026, 6, 15))
        advanceUntilIdle()

        val shown = viewModel.uiState.value.expandedEvents
        assertEquals(listOf("01BDAY", "01MEAL"), shown.map { it.id }.sorted())
        val day = java.time.LocalDate.of(2026, 6, 15)
        for (event in shown) {
            assertEquals(event.id, day, event.startDate)
            assertEquals(event.id, day.plusDays(1), event.endDate)
            assertNull(event.id, event.startAt)
        }
    }

    @Test
    fun `unticking Repas hides the meals and leaves the events`() = runTest {
        stubRepositories(events = listOf(event))
        coEvery { mealRepository.getMeals(any(), any()) } returns Result.success(listOf(dinner))
        val viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.navigateToDate(java.time.LocalDate.of(2026, 6, 15))
        advanceUntilIdle()

        viewModel.toggleMeals()

        assertFalse(viewModel.uiState.value.mealsEnabled)
        assertEquals(listOf("Dentiste"), viewModel.uiState.value.expandedEvents.map { it.summary })

        viewModel.toggleMeals()

        assertTrue(viewModel.uiState.value.mealsEnabled)
        assertEquals(2, viewModel.uiState.value.expandedEvents.size)
    }

    private val june15 = java.time.LocalDate.of(2026, 6, 15)
    private val juneNoon = Clock.fixed(Instant.parse("2026-06-15T10:00:00Z"), ZoneOffset.UTC)
    private fun proaction(
        id: String = "01PRO",
        summary: String = "Rappel école",
        startAt: String = "2026-06-15T08:30:00+02:00",
        executionStatus: String = Proaction.STATUS_PENDING,
    ) = Proaction(
        id = id,
        summary = summary,
        startAt = startAt,
        endAt = java.time.OffsetDateTime.parse(startAt).plusMinutes(5).toString(),
        prompt = "Rappelle de déposer Ael à l'école",
        executionStatus = executionStatus,
    )

    private fun TestScope.openJune(clock: Clock = juneNoon): FullCalendarViewModel {
        val viewModel = createViewModel(clock = clock)
        advanceUntilIdle()
        viewModel.navigateToDate(june15)
        advanceUntilIdle()
        return viewModel
    }

    @Test
    fun `the proactions of the visible range are read and drawn as timed events of the Maggie line`() = runTest {
        stubRepositories(events = listOf(event))
        coEvery { proactionRepository.getProactions(any(), any()) } returns Result.success(listOf(proaction()))

        val viewModel = openJune()

        val range = DateRanges.periodOfMonthGrid(june15).asRange()
        coVerify { proactionRepository.getProactions(range.start.toString(), range.end.toString()) }
        val drawn = viewModel.uiState.value.expandedEvents.single { it.agendaIri == "__proactions__" }
        assertEquals("01PRO", drawn.id)
        assertEquals("08:30 Rappel école", drawn.summary)
        assertEquals("#7C4DFF", drawn.agendaColor)
        assertEquals("Maggie", drawn.agendaName)
        assertFalse(drawn.allDay)
        assertEquals("2026-06-15T06:30:00Z", drawn.startAt)
        assertEquals("2026-06-15T06:35:00Z", drawn.endAt)
        assertTrue("Dentiste" in viewModel.uiState.value.expandedEvents.map { it.summary })
    }

    @Test
    fun `a past run shows a tick when completed and a warning when failed, a pending or future one nothing`() = runTest {
        stubRepositories()
        coEvery { proactionRepository.getProactions(any(), any()) } returns Result.success(
            listOf(
                proaction(id = "01DONE", summary = "Faite", startAt = "2026-06-14T08:30:00+02:00", executionStatus = Proaction.STATUS_COMPLETED),
                proaction(id = "01FAIL", summary = "Ratée", startAt = "2026-06-14T09:30:00+02:00", executionStatus = Proaction.STATUS_FAILED),
                proaction(id = "01LATE", summary = "Attend", startAt = "2026-06-14T10:30:00+02:00", executionStatus = Proaction.STATUS_PENDING),
                proaction(id = "01NEXT", summary = "Demain", startAt = "2026-06-16T08:30:00+02:00", executionStatus = Proaction.STATUS_COMPLETED),
            ),
        )

        val viewModel = openJune()

        val summaries = viewModel.uiState.value.expandedEvents.associate { it.id to it.summary }
        assertEquals("08:30 Faite ✓", summaries["01DONE"])
        assertEquals("09:30 Ratée ⚠", summaries["01FAIL"])
        assertEquals("10:30 Attend", summaries["01LATE"])
        assertEquals("08:30 Demain", summaries["01NEXT"])
    }

    @Test
    fun `unticking Maggie hides the proactions and leaves the events, ticking it brings them back`() = runTest {
        stubRepositories(events = listOf(event))
        coEvery { proactionRepository.getProactions(any(), any()) } returns Result.success(listOf(proaction()))
        val viewModel = openJune()

        viewModel.toggleProactions()

        assertFalse(viewModel.uiState.value.proactionsEnabled)
        assertEquals(listOf("Dentiste"), viewModel.uiState.value.expandedEvents.map { it.summary })

        viewModel.toggleProactions()

        assertTrue(viewModel.uiState.value.proactionsEnabled)
        assertEquals(2, viewModel.uiState.value.expandedEvents.size)
    }

    @Test
    fun `a proaction outside the visible range is not drawn`() = runTest {
        stubRepositories()
        coEvery { proactionRepository.getProactions(any(), any()) } returns
            Result.success(listOf(proaction(startAt = "2026-08-15T08:30:00+02:00")))

        val viewModel = openJune()

        assertTrue(viewModel.uiState.value.expandedEvents.isEmpty())
    }

    @Test
    fun `moving to another range reads the proactions of that range`() = runTest {
        stubRepositories()
        val viewModel = openJune()

        viewModel.setViewType(CalendarViewType.DAY)
        advanceUntilIdle()

        val day = DateRanges.periodOfDate(june15).asRange()
        coVerify { proactionRepository.getProactions(day.start.toString(), day.end.toString()) }
    }

    @Test
    fun `a slow reply for a range the user has left does not replace the proactions on screen`() = runTest {
        stubRepositories()
        val juneReplies = CompletableDeferred<Unit>()
        val juneRange = DateRanges.periodOfMonthGrid(june15).asRange()
        coEvery { proactionRepository.getProactions(juneRange.start.toString(), juneRange.end.toString()) } coAnswers {
            juneReplies.await()
            Result.success(listOf(proaction(summary = "Juin")))
        }
        val julyRange = DateRanges.periodOfMonthGrid(java.time.LocalDate.of(2026, 7, 15)).asRange()
        coEvery { proactionRepository.getProactions(julyRange.start.toString(), julyRange.end.toString()) } returns
            Result.success(listOf(proaction(id = "01JULY", summary = "Juillet", startAt = "2026-07-15T08:30:00+02:00")))
        val viewModel = createViewModel(clock = juneNoon)
        advanceUntilIdle()

        viewModel.navigateToDate(june15)
        advanceUntilIdle()
        viewModel.navigateToDate(java.time.LocalDate.of(2026, 7, 15))
        advanceUntilIdle()
        juneReplies.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("08:30 Juillet"), viewModel.uiState.value.expandedEvents.map { it.summary })
    }

    @Test
    fun `a proactions failure is reported and keeps the user's own calendar`() = runTest {
        stubRepositories(events = listOf(event), agendas = listOf(work))
        coEvery { proactionRepository.getProactions(any(), any()) } returns Result.failure(RuntimeException("proactions down"))

        val viewModel = openJune()

        val state = viewModel.uiState.value
        assertEquals("proactions down", state.error)
        assertEquals(listOf("Work"), state.agendas.map { it.name })
        assertEquals(listOf("Dentiste"), state.expandedEvents.map { it.summary })
        assertFalse(state.isLoading)
    }

    @Test
    fun `moving a proaction patches its start as an instant, then reads the range again`() = runTest {
        stubRepositories()
        var onServer = proaction()
        coEvery { proactionRepository.getProactions(any(), any()) } answers { Result.success(listOf(onServer)) }
        coEvery { proactionRepository.move("01PRO", any()) } answers {
            onServer = proaction(startAt = "2026-06-16T09:15:00+02:00")
            Result.success(Unit)
        }
        val viewModel = openJune()
        assertEquals(listOf("08:30 Rappel école"), viewModel.uiState.value.expandedEvents.map { it.summary })

        viewModel.moveProaction("01PRO", Instant.parse("2026-06-16T07:15:00Z"))
        advanceUntilIdle()

        coVerify(exactly = 1) { proactionRepository.move("01PRO", "2026-06-16T07:15:00Z") }
        val moved = viewModel.uiState.value.expandedEvents.single()
        assertEquals("09:15 Rappel école", moved.summary)
        assertEquals("2026-06-16T07:15:00Z", moved.startAt)
    }

    @Test
    fun `a move the API refuses is reported and the proaction stays where it was`() = runTest {
        stubRepositories()
        coEvery { proactionRepository.getProactions(any(), any()) } returns Result.success(listOf(proaction()))
        coEvery { proactionRepository.move(any(), any()) } returns Result.failure(RuntimeException("move refused"))
        val viewModel = openJune()

        viewModel.moveProaction("01PRO", Instant.parse("2026-06-16T07:15:00Z"))
        advanceUntilIdle()

        assertEquals("move refused", viewModel.uiState.value.error)
        assertEquals(listOf("08:30 Rappel école"), viewModel.uiState.value.expandedEvents.map { it.summary })
    }

    @Test
    fun `deleting a proaction removes it from the agenda`() = runTest {
        stubRepositories(events = listOf(event))
        var onServer = listOf(proaction())
        coEvery { proactionRepository.getProactions(any(), any()) } answers { Result.success(onServer) }
        coEvery { proactionRepository.delete("01PRO") } answers {
            onServer = emptyList()
            Result.success(Unit)
        }
        val viewModel = openJune()
        assertEquals(2, viewModel.uiState.value.expandedEvents.size)

        viewModel.deleteProaction("01PRO")
        advanceUntilIdle()

        coVerify(exactly = 1) { proactionRepository.delete("01PRO") }
        assertEquals(listOf("Dentiste"), viewModel.uiState.value.expandedEvents.map { it.summary })
        assertTrue(viewModel.uiState.value.proactions.isEmpty())
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `a delete the API refuses is reported and the proaction stays`() = runTest {
        stubRepositories()
        coEvery { proactionRepository.getProactions(any(), any()) } returns Result.success(listOf(proaction()))
        coEvery { proactionRepository.delete(any()) } returns Result.failure(RuntimeException("delete refused"))
        val viewModel = openJune()

        viewModel.deleteProaction("01PRO")
        advanceUntilIdle()

        assertEquals("delete refused", viewModel.uiState.value.error)
        assertEquals(listOf("08:30 Rappel école"), viewModel.uiState.value.expandedEvents.map { it.summary })
    }

    @Test
    fun `a proaction pushed on Mercure reloads the proactions and not the period`() = runTest {
        stubRepositories()
        val topics = mercureTopics()
        var onServer = emptyList<Proaction>()
        coEvery { proactionRepository.getProactions(any(), any()) } answers { Result.success(onServer) }
        val viewModel = openJune()
        assertTrue(viewModel.uiState.value.expandedEvents.isEmpty())
        val periodReads = 2

        onServer = listOf(proaction())
        topics.getValue(MercureTopics.PROACTIONS).emit(MercureEvent())
        advanceTimeBy(600)

        assertEquals(listOf("08:30 Rappel école"), viewModel.uiState.value.expandedEvents.map { it.summary })
        coVerify(exactly = periodReads) { eventRepository.loadPeriod(any(), any()) }
    }

    @Test
    fun `a module view reads no proaction`() = runTest {
        val viewModel = createViewModel(module = "cookbook")
        advanceUntilIdle()
        viewModel.navigateToDate(june15)
        advanceUntilIdle()

        coVerify(exactly = 0) { proactionRepository.getProactions(any(), any()) }
        assertTrue(viewModel.uiState.value.expandedEvents.isEmpty())
    }

    @Test
    fun `moving to another range fetches the meals of that range`() = runTest {
        stubRepositories()
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.navigateToDate(java.time.LocalDate.of(2026, 6, 15))
        advanceUntilIdle()

        coVerify { mealRepository.getMeals("2026-06-01", "2026-06-30") }

        viewModel.setViewType(CalendarViewType.WEEK)
        advanceUntilIdle()

        coVerify { mealRepository.getMeals("2026-06-15", "2026-06-21") }
    }

    @Test
    fun `a module view shows its meals only and reads nothing of the user's calendar`() = runTest {
        coEvery { mealRepository.getMeals(any(), any()) } returns Result.success(listOf(dinner))

        val viewModel = createViewModel(module = "cookbook")
        advanceUntilIdle()
        viewModel.navigateToDate(java.time.LocalDate.of(2026, 6, 15))
        advanceUntilIdle()

        assertEquals(listOf("Dîner: Gratin"), viewModel.uiState.value.expandedEvents.map { it.summary })
        assertTrue(viewModel.uiState.value.agendas.isEmpty())
        coVerify(exactly = 0) { agendaRepository.loadAgendas(any()) }
        coVerify(exactly = 0) { eventRepository.loadPeriod(any(), any()) }
        coVerify(exactly = 0) { taskRepository.refreshTasks() }
    }

    @Test
    fun `a meals failure is reported and keeps the calendar`() = runTest {
        stubRepositories(events = listOf(event))
        val viewModel = createViewModel()
        advanceUntilIdle()
        coEvery { mealRepository.getMeals(any(), any()) } returns Result.failure(RuntimeException("meals down"))

        viewModel.navigateToDate(java.time.LocalDate.of(2026, 6, 15))
        advanceUntilIdle()

        assertEquals("meals down", viewModel.uiState.value.error)
    }

    @Test
    fun `a meals failure on refresh keeps the agendas and the events of the user`() = runTest {
        stubRepositories(events = listOf(event), agendas = listOf(Agenda(id = "01WORK", name = "Work", color = "#FF0000")))
        coEvery { mealRepository.getMeals(any(), any()) } returns Result.failure(RuntimeException("meals down"))

        val viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf("Work"), state.agendas.map { it.name })
        assertFalse(state.isLoading)
        assertEquals("meals down", state.error)
    }

    @Test
    fun `a slow reply for a range the user has left does not replace the meals on screen`() = runTest {
        val june = Meal(id = "01JUNE", summary = "Juin", date = "2026-06-15", slot = MealSlot.DINNER)
        val july = Meal(id = "01JULY", summary = "Juillet", date = "2026-07-15", slot = MealSlot.DINNER)
        val juneReplies = CompletableDeferred<Unit>()
        coEvery { mealRepository.getMeals("2026-06-01", "2026-06-30") } coAnswers {
            juneReplies.await()
            Result.success(listOf(june))
        }
        coEvery { mealRepository.getMeals("2026-07-01", "2026-07-31") } returns Result.success(listOf(july))
        val viewModel = createViewModel(module = "cookbook")
        advanceUntilIdle()

        viewModel.navigateToDate(java.time.LocalDate.of(2026, 6, 15))
        advanceUntilIdle()
        viewModel.navigateToDate(java.time.LocalDate.of(2026, 7, 15))
        advanceUntilIdle()
        juneReplies.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("Dîner: Juillet"), viewModel.uiState.value.expandedEvents.map { it.summary })
    }

    @Test
    fun `a module view fetches the meals of the range it moves to`() = runTest {
        val viewModel = createViewModel(module = "cookbook")
        advanceUntilIdle()

        viewModel.navigateToDate(java.time.LocalDate.of(2026, 6, 15))
        advanceUntilIdle()
        viewModel.setViewType(CalendarViewType.WEEK)
        advanceUntilIdle()

        coVerify { mealRepository.getMeals("2026-06-01", "2026-06-30") }
        coVerify { mealRepository.getMeals("2026-06-15", "2026-06-21") }
        coVerify(exactly = 0) { eventRepository.loadPeriod(any(), any()) }
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
        coEvery { agendaRepository.loadAgendas(any()) } coAnswers { agendas.await() }
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

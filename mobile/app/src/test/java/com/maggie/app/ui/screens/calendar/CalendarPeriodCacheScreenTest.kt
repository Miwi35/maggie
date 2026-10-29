package com.maggie.app.ui.screens.calendar

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.local.MaggieDatabase
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.model.Agenda
import com.maggie.app.data.model.Event
import com.maggie.app.data.repository.AgendaRepository
import com.maggie.app.data.repository.EventRepository
import com.maggie.app.data.repository.MealRepository
import com.maggie.app.data.repository.ProactionRepository
import com.maggie.app.data.repository.TripRepository
import com.maggie.app.data.repository.TaskRepository
import com.maggie.app.data.repository.UserPreferenceRepository
import com.maggie.app.ui.screens.fullcalendar.CalendarViewType
import com.maggie.app.ui.screens.fullcalendar.FullCalendarViewModel
import com.maggie.app.util.DateRanges
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The month grid, from the API to the screen, over a real Room database (MAG-430): what a period
 * read puts in the tables is what the grid draws, whichever way the owner got to the month.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class CalendarPeriodCacheScreenTest {
    private lateinit var db: MaggieDatabase
    private val mainDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val api = mockk<MaggieApiService>()
    private val work = Agenda(id = "W", name = "Work", color = "#FF0000")

    @Before
    fun setUp() {
        // One thread runs every state update of the ViewModel (Room's invalidation, the period read,
        // the agendas): with Unconfined they resumed on Room's threads and overwrote each other's copy().
        Dispatchers.setMain(mainDispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MaggieDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        coEvery { api.getAgendas() } returns listOf(work)
        coEvery { api.getRecurringEventsBefore(any()) } returns emptyList()
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
        mainDispatcher.close()
    }

    private fun viewModel(): FullCalendarViewModel {
        val auth = mockk<AuthRepository>(relaxed = true)
        every { auth.token } returns flowOf("jwt")
        val mercure = mockk<MercureService>()
        every { mercure.subscribe(any()) } returns emptyFlow()
        val preferences = mockk<UserPreferenceRepository>()
        every { preferences.preference } returns MutableStateFlow(null)
        coEvery { preferences.refresh() } returns Result.failure(RuntimeException("offline"))
        val meals = mockk<MealRepository>()
        coEvery { meals.getMeals(any(), any()) } returns Result.success(emptyList())
        val proactions = mockk<ProactionRepository>()
        coEvery { proactions.getProactions(any(), any()) } returns Result.success(emptyList())
        val trips = mockk<TripRepository>()
        coEvery { trips.getTrips(any()) } returns Result.success(emptyList())
        val tasks = mockk<TaskRepository>()
        coEvery { tasks.refreshTasks() } returns Result.success(emptyList())
        coEvery { tasks.getUndoneTasks(any()) } returns emptyList()
        return FullCalendarViewModel(
            EventRepository(api, db.eventDao()),
            tasks,
            AgendaRepository(api, db.agendaDao()),
            mercure,
            auth,
            preferences,
            meals,
            proactions,
            trips,
        )
    }

    @Test
    fun `the month after the current one shows the events the API served in one answer`() = runBlocking {
        val nextMonth = DateRanges.todayDate().plusMonths(1).withDayOfMonth(1)
        coEvery { api.getEvents(any(), any()) } answers {
            (1..37).map { i ->
                val day = nextMonth.plusDays(((i - 1) % 25).toLong())
                Event(
                    id = "E$i",
                    summary = "Séance $i",
                    startAt = "${day}T09:00:00Z",
                    endAt = "${day}T09:30:00Z",
                    agenda = "/api/agendas/W",
                )
            }
        }

        val viewModel = viewModel()
        viewModel.setViewType(CalendarViewType.MONTH)
        viewModel.navigateForward()

        // Waits for the screen state, not for time: the bound only turns a hang into a failure.
        withTimeoutOrNull(60_000) { viewModel.uiState.first { it.expandedEvents.size >= 37 } }
        val shown = viewModel.uiState.value.expandedEvents.map { it.summary }

        assertEquals(
            "error=${viewModel.uiState.value.error}, agendas=${viewModel.uiState.value.enabledAgendas}",
            (1..37).map { "Séance $it" }.sorted(),
            shown.sorted(),
        )
    }
}

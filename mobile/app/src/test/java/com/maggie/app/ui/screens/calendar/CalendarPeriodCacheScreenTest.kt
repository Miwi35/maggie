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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
    private val api = mockk<MaggieApiService>()
    private val work = Agenda(id = "W", name = "Work", color = "#FF0000")

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MaggieDatabase::class.java).allowMainThreadQueries().build()
        coEvery { api.getAgendas() } returns listOf(work)
        coEvery { api.getRecurringEventsBefore(any()) } returns emptyList()
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
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
        )
    }

    private suspend fun FullCalendarViewModel.eventually(check: (List<String>) -> Boolean): List<String> {
        var last = emptyList<String>()
        withTimeoutOrNull(10_000) {
            while (true) {
                last = uiState.value.expandedEvents.map { it.summary }
                if (check(last)) break
                withContext(Dispatchers.Default) { delay(50) }
            }
        }
        return last
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

        val shown = viewModel.eventually { it.size >= 37 }

        assertEquals((1..37).map { "Séance $it" }.sorted(), shown.sorted())
    }
}

package com.maggie.app.ui.screens.fullcalendar

import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureEvent
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.model.Agenda
import com.maggie.app.data.model.Period
import com.maggie.app.data.repository.AgendaRepository
import com.maggie.app.data.repository.PeriodRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class PeriodViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val repository = mockk<PeriodRepository>()
    private val agendaRepository = mockk<AgendaRepository>()
    private val authRepository = mockk<AuthRepository>()
    private val mercureService = mockk<MercureService>()
    private val pushes = MutableSharedFlow<MercureEvent>()
    private val clock = Clock.fixed(Instant.parse("2026-10-25T10:00:00Z"), ZoneId.of("Europe/Paris"))

    private val custody = Agenda(id = "a1", name = "Garde", color = "#FF5722", isDefault = true)
    private val garde = Period(
        id = "p1", name = "Garde Léo", agenda = "/api/agendas/a1", allDay = true,
        startDate = "2026-10-20", endDate = "2026-10-30",
    )
    private val voyage = Period(
        id = "p2", name = "Voyage", agenda = "/api/agendas/a1", allDay = true,
        startDate = "2026-12-20", endDate = "2027-01-03",
    )

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        coEvery { repository.getPeriods() } returns Result.success(listOf(garde, voyage))
        coEvery { agendaRepository.loadAgendas() } returns Result.success(listOf(custody))
        coEvery { authRepository.getUserId() } returns "user-1"
        every { mercureService.subscribe(any()) } returns pushes
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = PeriodViewModel(repository, agendaRepository, mercureService, authRepository, clock)

    @Test
    fun `loading, then the periods and the agendas`() = runTest {
        val viewModel = viewModel()
        assertTrue(viewModel.uiState.value.isLoading)

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertTrue(state.hasLoaded)
        assertEquals(listOf("p1", "p2"), state.periods.map { it.id })
        assertEquals(listOf("a1"), state.agendas.map { it.id })
        assertEquals(listOf("En cours", "Décembre 2026"), viewModel.groups().map { it.title })
    }

    @Test
    fun `a failed load says so in French and keeps the list unloaded`() = runTest {
        coEvery { repository.getPeriods() } returns Result.failure(RuntimeException("boom"))
        val viewModel = viewModel()
        advanceUntilIdle()

        assertEquals(PeriodViewModel.MESSAGE_LOAD, viewModel.uiState.value.error)
        assertFalse(viewModel.uiState.value.hasLoaded)
    }

    @Test
    fun `a push on the periods topic reads the list again`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()
        coEvery { repository.getPeriods() } returns Result.success(listOf(voyage))

        pushes.emit(MercureEvent(data = "{}"))
        advanceUntilIdle()

        assertEquals(listOf("p2"), viewModel.uiState.value.periods.map { it.id })
    }

    @Test
    fun `the Passées tab switches the groups`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.selectTab(PeriodTab.PAST)

        assertEquals(PeriodTab.PAST, viewModel.uiState.value.tab)
        assertTrue(viewModel.groups().isEmpty())
    }

    @Test
    fun `a new period opens on the default agenda, a week of days from today`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.openNew(LocalDate.of(2026, 10, 25))

        val form = viewModel.uiState.value.form!!
        assertTrue(form.isNew)
        assertEquals("/api/agendas/a1", form.agendaIri)
        assertTrue(form.dates.allDay)
        assertEquals(LocalDate.of(2026, 10, 25), form.dates.startDate)
        assertEquals(LocalDate.of(2026, 10, 31), form.dates.endDate)
    }

    @Test
    fun `editing opens the form on the period and selects its row, an unknown one says it is gone`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.openEdit("p1")
        assertEquals("p1", viewModel.uiState.value.selectedId)
        assertEquals("Garde Léo", viewModel.uiState.value.form?.name)
        assertEquals(LocalDate.of(2026, 10, 29), viewModel.uiState.value.form?.dates?.endDate)

        viewModel.openEdit("nope")
        assertNull(viewModel.uiState.value.form)
        assertEquals(PeriodViewModel.MESSAGE_NOT_FOUND, viewModel.uiState.value.error)
    }

    @Test
    fun `saving without a name shows the error and sends nothing`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.openNew(LocalDate.of(2026, 10, 25))

        viewModel.save()
        advanceUntilIdle()

        val form = viewModel.uiState.value.form!!
        assertTrue(form.showErrors)
        assertEquals("Donnez un nom à la période.", form.shownErrors[PeriodField.NAME])
        coVerify(exactly = 0) { repository.createPeriod(any()) }
    }

    @Test
    fun `saving a new period sends its dates with the end excluded, then closes the form`() = runTest {
        val body = slot<JsonObject>()
        coEvery { repository.createPeriod(capture(body)) } returns Result.success(garde)
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.openNew(LocalDate.of(2026, 10, 25))
        viewModel.editForm { it.copy(name = "Vacances", rrule = "FREQ=WEEKLY;INTERVAL=2") }

        viewModel.save()
        advanceUntilIdle()

        assertEquals(JsonPrimitive("Vacances"), body.captured["name"])
        assertEquals(JsonPrimitive("/api/agendas/a1"), body.captured["agenda"])
        assertEquals(JsonPrimitive("2026-10-25"), body.captured["startDate"])
        assertEquals(JsonPrimitive("2026-11-01"), body.captured["endDate"])
        assertEquals(JsonNull, body.captured["startAt"])
        assertEquals(JsonPrimitive("FREQ=WEEKLY;INTERVAL=2"), body.captured["rrule"])
        assertTrue(viewModel.uiState.value.form!!.done)
        assertEquals("Période créée", viewModel.uiState.value.message)
    }

    @Test
    fun `saving an existing period patches it without touching its repetition`() = runTest {
        val body = slot<JsonObject>()
        coEvery { repository.updatePeriod("p1", capture(body)) } returns Result.success(garde)
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.openEdit("p1")
        viewModel.editForm { it.copy(name = "Garde de Léo") }

        viewModel.save()
        advanceUntilIdle()

        assertEquals(JsonPrimitive("Garde de Léo"), body.captured["name"])
        assertEquals(JsonPrimitive("2026-10-30"), body.captured["endDate"])
        assertFalse(body.captured.containsKey("rrule"))
        assertEquals("Période enregistrée", viewModel.uiState.value.message)
    }

    @Test
    fun `a refused save keeps the form open with a French message`() = runTest {
        coEvery { repository.createPeriod(any()) } returns Result.failure(RuntimeException("422"))
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.openNew(LocalDate.of(2026, 10, 25))
        viewModel.editForm { it.copy(name = "Vacances") }

        viewModel.save()
        advanceUntilIdle()

        val form = viewModel.uiState.value.form!!
        assertFalse(form.done)
        assertFalse(form.isSaving)
        assertEquals(PeriodViewModel.MESSAGE_SAVE, form.error)
    }

    @Test
    fun `deleting takes the row out at once and confirms`() = runTest {
        coEvery { repository.deletePeriod("p1") } returns Result.success(Unit)
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.openEdit("p1")

        viewModel.delete("p1")
        assertEquals(listOf("p2"), viewModel.uiState.value.periods.map { it.id })
        advanceUntilIdle()

        assertEquals("Période supprimée", viewModel.uiState.value.message)
        assertTrue(viewModel.uiState.value.form!!.done)
    }

    @Test
    fun `a refused delete brings the row back with the reason`() = runTest {
        coEvery { repository.deletePeriod("p1") } returns Result.failure(RuntimeException("403"))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.delete("p1")
        advanceUntilIdle()

        assertEquals(listOf("p1", "p2"), viewModel.uiState.value.periods.map { it.id })
        assertEquals(PeriodViewModel.MESSAGE_DELETE, viewModel.uiState.value.error)
    }
}

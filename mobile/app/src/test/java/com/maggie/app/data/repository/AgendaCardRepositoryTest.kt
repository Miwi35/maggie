package com.maggie.app.data.repository

import com.maggie.app.data.api.ApiRefusalException
import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureEvent
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.model.Agenda
import com.maggie.app.data.model.Event
import com.maggie.app.data.model.HomeDay
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The reads of the Agenda card (MAG-589): the events resource with its series unfolded and the agendas, never the home
 * card endpoint; a module absent (404) is an empty day; the topics of events and agendas wake it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgendaCardRepositoryTest {
    private val api = mockk<MaggieApiService>()
    private val mercure = mockk<MercureService>()
    private val auth = mockk<AuthRepository> { coEvery { getUserId() } returns "01HUSER" }
    private val repository = AgendaCardRepository(api, mercure, auth)

    private val today = LocalDate.of(2026, 10, 23)

    private val perso = Agenda(id = "ag1", name = "Perso", color = "#336699")
    private val travail = Agenda(id = "ag2", name = "Travail", color = "#CC3300")

    private fun timed(id: String, summary: String, start: String, end: String, agenda: String = "ag1", location: String? = null) = Event(
        id = id,
        summary = summary,
        location = location,
        startAt = "2026-10-23T$start:00+02:00",
        endAt = "2026-10-23T$end:00+02:00",
        agenda = "/api/agendas/$agenda",
    )

    private val stay = Event(
        id = "stay",
        summary = "Séjour",
        allDay = true,
        startDate = "2026-10-22",
        endDate = "2026-10-25",
        agenda = "/api/agendas/ag2",
    )

    private fun answers(vararg events: Event) {
        coEvery { api.getExpandedEvents(any(), any()) } returns events.toList()
        coEvery { api.getAgendas() } returns listOf(perso, travail)
    }

    @Test
    fun `today asks the events of the owner's day, unfolded, between its midnights`() = runTest {
        answers()

        repository.read(HomeDay.TODAY, today).getOrThrow()

        // Paris is on UTC+2 on the 23rd and the 24th of October 2026.
        coVerify(exactly = 1) { api.getExpandedEvents("2026-10-22T22:00:00Z", "2026-10-23T22:00:00Z") }
        coVerify(exactly = 1) { api.getAgendas() }
    }

    @Test
    fun `tomorrow asks the next day, which is the day after today across a change of hour`() = runTest {
        answers()

        val state = repository.read(HomeDay.TOMORROW, LocalDate.of(2026, 10, 24)).getOrThrow()

        // The clocks go back on the night of the 24th to the 25th: that day is 25 hours long.
        coVerify(exactly = 1) { api.getExpandedEvents("2026-10-24T22:00:00Z", "2026-10-25T23:00:00Z") }
        assertEquals("2026-10-25", state.date)
    }

    @Test
    fun `the day carries its date and the rows take the name and colour of their agenda`() = runTest {
        answers(timed("e1", "Dentiste", "15:00", "16:00", agenda = "ag2"))

        val state = repository.read(HomeDay.TODAY, today).getOrThrow()

        assertEquals("2026-10-23", state.date)
        val row = state.events.single()
        assertEquals(listOf("ag2", "Travail", "#CC3300"), listOf(row.agendaId, row.agendaName, row.agendaColor))
    }

    @Test
    fun `an event of an agenda the list does not hold has no name nor colour`() = runTest {
        answers(timed("e1", "Dentiste", "15:00", "16:00", agenda = "gone"))

        val row = repository.read(HomeDay.TODAY, today).getOrThrow().events.single()

        assertEquals("gone", row.agendaId)
        assertNull(row.agendaName)
        assertNull(row.agendaColor)
    }

    @Test
    fun `the place is shortened to what comes before the address`() = runTest {
        answers(timed("e1", "Dentiste", "15:00", "16:00", location = "Cabinet Martin, 15 rue de la Paix"))

        val row = repository.read(HomeDay.TODAY, today).getOrThrow().events.single()

        assertEquals("Cabinet Martin", row.locationShort)
        assertEquals("Cabinet Martin, 15 rue de la Paix", row.location)
    }

    @Test
    fun `a whole-day event keeps its dates, the end excluded`() = runTest {
        answers(stay)

        val row = repository.read(HomeDay.TODAY, today).getOrThrow().events.single()

        assertEquals(LocalDate.of(2026, 10, 22), row.startDate)
        assertEquals(LocalDate.of(2026, 10, 25), row.endDate)
    }

    @Test
    fun `the day is ordered whole-day first, then by start, then by title`() = runTest {
        answers(
            timed("late", "Dîner", "20:00", "22:00"),
            timed("b", "Bilan", "09:00", "10:00"),
            stay,
            timed("a", "Atelier", "09:00", "10:00"),
        )

        val ids = repository.read(HomeDay.TODAY, today).getOrThrow().events.map { it.id }

        assertEquals(listOf("stay", "a", "b", "late"), ids)
    }

    @Test
    fun `an unfolded series appears with the series id, once per occurrence`() = runTest {
        answers(timed("series", "Sport", "08:00", "09:00"), timed("series", "Sport", "18:00", "19:00"))

        val rows = repository.read(HomeDay.TODAY, today).getOrThrow().events

        assertEquals(listOf("series", "series"), rows.map { it.id })
        assertEquals(listOf("08:00", "18:00"), rows.map { it.startAt!!.substring(11, 16) })
    }

    @Test
    fun `the two reads start together`() = runTest {
        val gate = CompletableDeferred<Unit>()
        coEvery { api.getExpandedEvents(any(), any()) } coAnswers { gate.await(); emptyList() }
        coEvery { api.getAgendas() } coAnswers { gate.await(); emptyList() }

        val read = launch { repository.read(HomeDay.TODAY, today) }
        runCurrent()

        coVerify(exactly = 1) { api.getExpandedEvents(any(), any()) }
        coVerify(exactly = 1) { api.getAgendas() }
        gate.complete(Unit)
        read.join()
    }

    @Test
    fun `a read answered 404 gives a day with no event, which the Accueil does not draw`() = runTest {
        answers()
        coEvery { api.getExpandedEvents(any(), any()) } throws ApiRefusalException(404, "Not found")
        assertTrue(repository.read(HomeDay.TODAY, today).getOrThrow().events.isEmpty())

        answers()
        coEvery { api.getAgendas() } throws ApiRefusalException(404, "Not found")
        val state = repository.read(HomeDay.TOMORROW, today).getOrThrow()

        assertTrue(state.events.isEmpty())
        assertEquals("2026-10-24", state.date)
    }

    @Test
    fun `any other failure is a failure`() = runTest {
        answers()
        coEvery { api.getExpandedEvents(any(), any()) } throws ApiRefusalException(500, "boom")
        assertTrue(repository.read(HomeDay.TODAY, today).isFailure)

        answers()
        coEvery { api.getAgendas() } throws RuntimeException("offline")
        assertTrue(repository.read(HomeDay.TODAY, today).isFailure)
    }

    @Test
    fun `a change on events or agendas wakes the card, bursts merged`() = runTest {
        every { mercure.subscribe("/users/01HUSER/api/events/{id}") } returns flowOf(MercureEvent(id = "1"), MercureEvent(id = "2"))
        every { mercure.subscribe("/users/01HUSER/api/agendas/{id}") } returns flowOf()

        val changes = repository.changes().toList()

        assertEquals(1, changes.size)
        verify(exactly = 1) { mercure.subscribe("/users/01HUSER/api/events/{id}") }
        verify(exactly = 1) { mercure.subscribe("/users/01HUSER/api/agendas/{id}") }
        verify(exactly = 0) { mercure.subscribe(match { it.contains("/home/") }) }
    }

    @Test
    fun `an agenda renamed elsewhere wakes the card`() = runTest {
        val agendas = MutableSharedFlow<MercureEvent>()
        every { mercure.subscribe("/users/01HUSER/api/events/{id}") } returns flowOf()
        every { mercure.subscribe("/users/01HUSER/api/agendas/{id}") } returns agendas
        var woken = 0
        val job = launch { repository.changes().collect { woken++ } }
        runCurrent()

        agendas.emit(MercureEvent(id = "1"))
        advanceUntilIdle()

        assertEquals(1, woken)
        job.cancel()
    }

    @Test
    fun `without a signed-in user the card subscribes to nothing`() = runTest {
        coEvery { auth.getUserId() } returns null

        assertTrue(repository.changes().toList().isEmpty())
        verify(exactly = 0) { mercure.subscribe(any()) }
    }
}

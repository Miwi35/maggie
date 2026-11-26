package com.maggie.app.data.repository

import com.maggie.app.data.api.EventCreateRequest
import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.model.Event
import com.maggie.app.data.model.Proaction
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ProactionRepositoryTest {

    private val api = mockk<MaggieApiService>()
    private val repository = ProactionRepository(api)
    private val series = Proaction(id = "01SER", summary = "Rappel", agenda = "/api/agendas/01AG", rrule = "FREQ=WEEKLY;BYDAY=FR")

    @Test
    fun `the range is read with the series that started before it, once each`() = runTest {
        coEvery { api.getProactions("2026-06-01T00:00:00Z", "2026-07-01T00:00:00Z") } returns listOf(series, Proaction(id = "01B"))
        coEvery { api.getProactionSeries("2026-06-01T00:00:00Z") } returns listOf(series, Proaction(id = "01OLD", rrule = "FREQ=DAILY"))

        val all = repository.getProactions("2026-06-01T00:00:00Z", "2026-07-01T00:00:00Z").getOrThrow()

        assertEquals(listOf("01SER", "01B", "01OLD"), all.map { it.id })
    }

    @Test
    fun `moving an occurrence posts an exception of the series at the new instant`() = runTest {
        val request = slot<EventCreateRequest>()
        coEvery { api.createEvent(capture(request)) } returns mockk<Event>()

        repository.moveOccurrence(series, "2026-06-19T16:00:00Z", "2026-06-20T07:15:00Z").getOrThrow()

        assertEquals("/api/events/01SER", request.captured.recurringEvent)
        assertEquals("2026-06-19T16:00:00Z", request.captured.originalStartAt)
        assertEquals("2026-06-20T07:15:00Z", request.captured.startAt)
        assertEquals("confirmed", request.captured.status)
        assertEquals("/api/agendas/01AG", request.captured.agenda)
        coVerify(exactly = 0) { api.updateEvent(any(), any()) }
    }

    @Test
    fun `cancelling an occurrence posts a cancelled exception`() = runTest {
        val request = slot<EventCreateRequest>()
        coEvery { api.createEvent(capture(request)) } returns mockk<Event>()

        repository.cancelOccurrence(series, "2026-06-19T16:00:00Z").getOrThrow()

        assertEquals("cancelled", request.captured.status)
        assertEquals("2026-06-19T16:00:00Z", request.captured.originalStartAt)
        assertEquals("/api/events/01SER", request.captured.recurringEvent)
    }
}

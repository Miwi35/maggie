package com.maggie.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.local.MaggieDatabase
import com.maggie.app.data.local.dao.EventDao
import com.maggie.app.data.local.entity.EventEntity
import com.maggie.app.data.local.entity.SyncStatus
import com.maggie.app.data.model.Event
import com.maggie.app.util.DayPeriod
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.hours

/**
 * The period cache of the calendar (MAG-430), against a real Room database: a period read in full is
 * served from the table for an hour, the pushes keep it current, and a read replaces only what it asked about.
 */
@RunWith(AndroidJUnit4::class)
class EventPeriodCacheTest {

    private class MutableClock(var now: Instant) : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
        override fun instant() = now
    }

    private lateinit var db: MaggieDatabase
    private lateinit var dao: EventDao
    private val api = mockk<MaggieApiService>()
    private val clock = MutableClock(Instant.parse("2026-06-10T08:00:00Z"))
    private lateinit var events: EventRepository

    private val june = DayPeriod(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 7, 6))
    private val july = DayPeriod(LocalDate.of(2026, 7, 6), LocalDate.of(2026, 8, 3))

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MaggieDatabase::class.java).allowMainThreadQueries().build()
        dao = db.eventDao()
        events = EventRepository(api, dao, clock, 1.hours)
        coEvery { api.getEvents(any(), any()) } returns emptyList()
        coEvery { api.getRecurringEventsBefore(any()) } returns emptyList()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun event(id: String, day: String) =
        Event(id = id, summary = "RDV $id", startAt = "${day}T10:00:00Z", endAt = "${day}T11:00:00Z")

    private fun cachedIds() = runBlocking { dao.getAll().map { it.id }.sorted() }

    @Test
    fun `a period is requested once with its bounds in Paris and cached`() = runBlocking {
        coEvery { api.getEvents(any(), any()) } returns (1..37).map { event("E$it", "2026-06-%02d".format(1 + it % 28)) }

        assertTrue(events.loadPeriod(june).isSuccess)

        coVerify(exactly = 1) { api.getEvents("2026-05-31T22:00:00Z", "2026-07-05T22:00:00Z") }
        coVerify(exactly = 1) { api.getRecurringEventsBefore("2026-07-05T22:00:00Z") }
        assertEquals(37, cachedIds().size)
    }

    @Test
    fun `revisiting a period within the hour makes no API call`() = runBlocking {
        events.loadPeriod(june)
        clock.now = clock.now.plusSeconds(59 * 60)

        events.loadPeriod(june)

        coVerify(exactly = 1) { api.getEvents(any(), any()) }
        coVerify(exactly = 1) { api.getRecurringEventsBefore(any()) }
    }

    @Test
    fun `a day inside a period read lately is served by the cache, a new month is requested`() = runBlocking {
        events.loadPeriod(june)

        events.loadPeriod(DayPeriod(LocalDate.of(2026, 6, 15), LocalDate.of(2026, 6, 16)))
        coVerify(exactly = 1) { api.getEvents(any(), any()) }

        events.loadPeriod(july)
        coVerify(exactly = 2) { api.getEvents(any(), any()) }
        coVerify { api.getEvents("2026-07-05T22:00:00Z", "2026-08-02T22:00:00Z") }
    }

    @Test
    fun `after the hour the period is read again in full`() = runBlocking {
        events.loadPeriod(june)
        clock.now = clock.now.plusSeconds(61 * 60)

        events.loadPeriod(june)

        coVerify(exactly = 2) { api.getEvents(any(), any()) }
    }

    @Test
    fun `a forced load reads again whatever the cache holds`() = runBlocking {
        events.loadPeriod(june)

        events.loadPeriod(june, force = true)

        coVerify(exactly = 2) { api.getEvents(any(), any()) }
    }

    @Test
    fun `forgetting the periods makes the next load read again`() = runBlocking {
        events.loadPeriod(june)

        events.forgetFetchedPeriods()
        events.loadPeriod(june)

        coVerify(exactly = 2) { api.getEvents(any(), any()) }
    }

    @Test
    fun `a failed read leaves the cache as it was and is not remembered`() = runBlocking {
        coEvery { api.getEvents(any(), any()) } returns listOf(event("A", "2026-06-10"))
        events.loadPeriod(june)
        coEvery { api.getEvents(any(), any()) } throws RuntimeException("offline")

        assertTrue(events.loadPeriod(june, force = true).isFailure)
        assertTrue(events.loadPeriod(DayPeriod(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 7, 6)), force = true).isFailure)

        assertEquals(listOf("A"), cachedIds())
    }

    @Test
    fun `a re-read drops what the server no longer has for the period, and nothing else`() = runBlocking {
        dao.upsertAll(
            listOf(
                EventEntity.fromModel(event("gone", "2026-06-12")),
                EventEntity.fromModel(event("kept", "2026-06-13")),
                EventEntity.fromModel(event("outside", "2026-09-01")),
                EventEntity.fromModel(event("pending", "2026-06-14")).copy(syncStatus = SyncStatus.PENDING_CREATE),
            ),
        )
        coEvery { api.getEvents(any(), any()) } returns listOf(event("kept", "2026-06-13"), event("new", "2026-06-20"))

        events.loadPeriod(june)

        assertEquals(listOf("kept", "new", "outside", "pending"), cachedIds())
    }

    @Test
    fun `an event just after midnight in Paris on the next period's first day is not dropped by this period's re-read`() = runBlocking {
        // Monday 6 July 00:30 in Paris is Sunday 5 July 22:30 in UTC: the day prefix says the 5th, the period ends on the 6th.
        val lateNight = Event(id = "late", summary = "Nuit", startAt = "2026-07-05T22:30:00+00:00", endAt = "2026-07-05T23:30:00+00:00")
        dao.upsertAll(listOf(EventEntity.fromModel(lateNight)))

        events.loadPeriod(june)

        assertEquals(listOf("late"), cachedIds())
    }

    @Test
    fun `an event that began before the period and meets it is dropped when the server no longer has it`() = runBlocking {
        val holiday = Event(id = "holiday", summary = "Congés", allDay = true, startDate = "2026-05-28", endDate = "2026-06-03")
        val ending = Event(id = "ending", summary = "Séminaire", startAt = "2026-05-31T20:00:00+00:00", endAt = "2026-06-01T10:00:00+00:00")
        val before = Event(id = "before", summary = "Avant", startAt = "2026-05-31T20:00:00+00:00", endAt = "2026-05-31T21:00:00+00:00")
        dao.upsertAll(listOf(holiday, ending, before).map { EventEntity.fromModel(it) })

        events.loadPeriod(june)

        assertEquals(listOf("before"), cachedIds())
    }

    @Test
    fun `a recurring event the server no longer returns is dropped by the re-read`() = runBlocking {
        val weekly = Event(id = "weekly", summary = "Sport", startAt = "2026-01-05T18:00:00Z", endAt = "2026-01-05T19:00:00Z", rrule = "FREQ=WEEKLY")
        coEvery { api.getRecurringEventsBefore(any()) } returns listOf(weekly)
        events.loadPeriod(june)
        coEvery { api.getRecurringEventsBefore(any()) } returns emptyList()

        events.loadPeriod(june, force = true)

        assertEquals(emptyList<String>(), cachedIds())
    }

    @Test
    fun `a push puts the event in the cache without reading any period`() = runBlocking {
        events.loadPeriod(june)
        coEvery { api.getEvent("NEW") } returns event("NEW", "2026-06-20")

        events.applyPushes(mapOf("NEW" to false))

        assertEquals(listOf("NEW"), cachedIds())
        coVerify(exactly = 1) { api.getEvents(any(), any()) }
        events.loadPeriod(june)
        coVerify(exactly = 1) { api.getEvents(any(), any()) }
    }

    @Test
    fun `a push replaces the event with the server's version`() = runBlocking {
        dao.upsertAll(listOf(EventEntity.fromModel(event("A", "2026-06-10"))))
        coEvery { api.getEvent("A") } returns event("A", "2026-06-10").copy(summary = "Déplacé")

        events.applyPushes(mapOf("A" to false))

        assertEquals("Déplacé", dao.getById("A")?.summary)
    }

    @Test
    fun `a delete push removes the event`() = runBlocking {
        dao.upsertAll(listOf(EventEntity.fromModel(event("A", "2026-06-10")), EventEntity.fromModel(event("B", "2026-06-11"))))

        events.applyPushes(mapOf("A" to true))

        assertEquals(listOf("B"), cachedIds())
        coVerify(exactly = 0) { api.getEvent(any()) }
    }

    @Test
    fun `a push for an event the server does not serve is ignored`() = runBlocking {
        coEvery { api.getEvent("MEAL") } throws RuntimeException("404")

        events.applyPushes(mapOf("MEAL" to false))

        assertEquals(emptyList<String>(), cachedIds())
    }

    @Test
    fun `a bulk push re-reads the periods read lately instead of event after event`() = runBlocking {
        events.loadPeriod(june)
        events.loadPeriod(july)
        coEvery { api.getEvents(any(), any()) } returns listOf(event("SYNCED", "2026-06-20"))

        events.applyPushes((1..PUSH_BULK_LIMIT + 1).associate { "E$it" to false })

        coVerify(exactly = 0) { api.getEvent(any()) }
        coVerify(exactly = 4) { api.getEvents(any(), any()) }
        assertEquals(listOf("SYNCED"), cachedIds())
    }

    @Test
    fun `deleting an agenda drops its events from the cache`() = runBlocking {
        dao.upsertAll(
            listOf(
                EventEntity.fromModel(event("A", "2026-06-10").copy(agenda = "/api/agendas/G1")),
                EventEntity.fromModel(event("B", "2026-06-11").copy(agenda = "/api/agendas/G2")),
            ),
        )

        events.dropEventsOfAgenda("G1")

        assertEquals(listOf("B"), cachedIds())
    }
}

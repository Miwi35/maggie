package com.maggie.app.ui.screens.fullcalendar

import com.maggie.app.data.model.Period
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class PeriodPresentationTest {

    private val zone = ZoneId.of("Europe/Paris")
    private val now = Instant.parse("2026-10-25T10:00:00Z")

    private fun timed(id: String, start: String, end: String, rrule: String? = null) =
        Period(id = id, name = id, startAt = start, endAt = end, rrule = rrule)

    private fun days(id: String, start: String, end: String, rrule: String? = null) =
        Period(id = id, name = id, allDay = true, startDate = start, endDate = end, rrule = rrule)

    @Test
    fun `a timed period shows its weekday, day, month and time at both ends, with its repetition`() {
        val period = timed("garde", "2026-10-23T16:00:00Z", "2026-10-30T17:00:00Z", "FREQ=WEEKLY;INTERVAL=2")
        assertEquals("ven. 23 oct. 18:00 → ven. 30 oct. 18:00 · une semaine sur deux", periodDatesLabel(period))
    }

    @Test
    fun `a period of days shows its last day included, the first of the month as 1er`() {
        assertEquals("18 oct. → 1er nov.", periodDatesLabel(days("v", "2026-10-18", "2026-11-02")))
        assertEquals("18 oct.", periodDatesLabel(days("one", "2026-10-18", "2026-10-19")))
    }

    @Test
    fun `the chip says until when, in the form of the period`() {
        assertEquals("jusqu'au ven. 18:00", periodUntilLabel(timed("t", "2026-10-23T16:00:00Z", "2026-10-30T17:00:00Z")))
        assertEquals("jusqu'au 1er nov.", periodUntilLabel(days("d", "2026-10-18", "2026-11-02")))
    }

    @Test
    fun `no repetition has no label, every other week is said in words`() {
        assertNull(recurrenceLabel(null))
        assertEquals("une semaine sur deux", recurrenceLabel("FREQ=WEEKLY;INTERVAL=2"))
        assertEquals("toutes les semaines", recurrenceLabel("FREQ=WEEKLY"))
    }

    @Test
    fun `upcoming puts the period in progress first, then the coming ones by month`() {
        val ongoing = days("now", "2026-10-20", "2026-10-30")
        val december = days("dec", "2026-12-20", "2027-01-03")
        val november = days("nov", "2026-11-10", "2026-11-12")
        val past = days("old", "2026-09-01", "2026-09-05")

        val groups = periodGroups(listOf(december, past, november, ongoing), PeriodTab.UPCOMING, now, zone)

        assertEquals(listOf("En cours", "Novembre 2026", "Décembre 2026"), groups.map { it.title })
        assertEquals(listOf("now"), groups[0].periods.map { it.id })
        assertEquals(listOf("nov"), groups[1].periods.map { it.id })
    }

    @Test
    fun `past lists the ended periods, the latest first, and a series that never ends is never past`() {
        val september = days("sep", "2026-09-01", "2026-09-05")
        val august = days("aug", "2026-08-01", "2026-08-05")
        val forever = days("forever", "2026-01-01", "2026-01-05", "FREQ=WEEKLY;INTERVAL=2")

        val past = periodGroups(listOf(august, forever, september), PeriodTab.PAST, now, zone)
        assertEquals(listOf("Septembre 2026", "Août 2026"), past.map { it.title })

        val upcoming = periodGroups(listOf(forever), PeriodTab.UPCOMING, now, zone)
        assertEquals(listOf("En cours"), upcoming.map { it.title })
    }
}

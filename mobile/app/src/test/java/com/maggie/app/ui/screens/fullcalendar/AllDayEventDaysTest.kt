package com.maggie.app.ui.screens.fullcalendar

import com.maggie.app.data.model.ExpandedEvent
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * MAG-382: a one-day event showed on two days. Google's birthday on 1 January was
 * stored 2037-01-01 00:00 UTC → 2037-01-02 00:00 UTC; converted to Paris its end
 * fell at 01:00 on the 2nd, and the day, week and month views drew it on both days.
 *
 * Here the event is a pair of dates, the end exclusive as in Google (1 January:
 * startDate 1, endDate 2), and the three views must draw it on exactly its days.
 */
class AllDayEventDaysTest {

    private val paris = ZoneId.of("Europe/Paris")

    private val newYear = LocalDate.of(2037, 1, 1)
    private val birthday = allDay("birthday", newYear, LocalDate.of(2037, 1, 2))

    // From the 26th to the 28th: endDate is the 29th.
    private val stay = allDay("stay", LocalDate.of(2037, 1, 26), LocalDate.of(2037, 1, 29))

    @Test
    fun `the day view shows a one-day event on its day only`() {
        assertEquals(listOf("birthday"), eventsForDate(listOf(birthday), newYear, paris).first.map { it.id })
        assertEquals(emptyList<String>(), eventsForDate(listOf(birthday), newYear.plusDays(1), paris).first.map { it.id })
        assertEquals(emptyList<String>(), eventsForDate(listOf(birthday), newYear.minusDays(1), paris).first.map { it.id })
    }

    @Test
    fun `the day view shows a three-day event on each of its days, not the day after`() {
        for (day in 26..28) {
            assertEquals("on the $day", listOf("stay"), eventsForDate(listOf(stay), LocalDate.of(2037, 1, day), paris).first.map { it.id })
        }
        assertEquals(emptyList<String>(), eventsForDate(listOf(stay), LocalDate.of(2037, 1, 29), paris).first.map { it.id })
        assertEquals(emptyList<String>(), eventsForDate(listOf(stay), LocalDate.of(2037, 1, 25), paris).first.map { it.id })
    }

    @Test
    fun `the week view draws a one-day event over its day only`() {
        // 2037-01-01 is a Thursday: the week runs from Monday 29 December 2036.
        val days = weekOf(newYear)
        val slots = computeWeekSpanSlots(listOf(birthday), days, paris)

        assertEquals(1, slots.size)
        assertEquals(newYear, days[slots.single().startDayIndex])
        assertEquals(newYear, days[slots.single().endDayIndex])
    }

    @Test
    fun `the week view draws a three-day event from its first to its last day`() {
        val days = weekOf(LocalDate.of(2037, 1, 26))
        val slot = computeWeekSpanSlots(listOf(stay), days, paris).single()

        assertEquals(LocalDate.of(2037, 1, 26), days[slot.startDayIndex])
        assertEquals(LocalDate.of(2037, 1, 28), days[slot.endDayIndex])
    }

    @Test
    fun `the month view puts a one-day event in its day only`() {
        val slots = computeSlots(listOf(birthday), paris)

        assertEquals(setOf(newYear), slots.filterValues { it.isNotEmpty() }.keys)
    }

    @Test
    fun `the month view puts a three-day event in its three days, not the 29th`() {
        val slots = computeSlots(listOf(stay), paris)

        assertEquals(
            (26..28).map { LocalDate.of(2037, 1, it) }.toSet(),
            slots.filterValues { it.isNotEmpty() }.keys,
        )
    }

    private fun weekOf(day: LocalDate): List<LocalDate> {
        val monday = day.with(java.time.DayOfWeek.MONDAY)
        return (0L..6L).map { monday.plusDays(it) }
    }

    /** As the API serves it since MAG-382: its start date and exclusive end date, no instant. */
    private fun allDay(id: String, start: LocalDate, end: LocalDate) = ExpandedEvent(
        id = id,
        summary = id,
        allDay = true,
        startDate = start,
        endDate = end,
    )
}

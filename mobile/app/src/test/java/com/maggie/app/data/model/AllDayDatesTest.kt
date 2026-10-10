package com.maggie.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * MAG-382: the API's end date is exclusive (Google's `end.date`), the screens show
 * and take the last day included. These two functions are the only crossing.
 */
class AllDayDatesTest {

    @Test
    fun `the last day shown is the day before the exclusive end`() {
        assertEquals(LocalDate.of(2037, 1, 1), lastDayOf(LocalDate.of(2037, 1, 2)))
        assertEquals(LocalDate.of(2037, 1, 28), lastDayOf(LocalDate.of(2037, 1, 29)))
        assertEquals(LocalDate.of(2036, 12, 31), lastDayOf(LocalDate.of(2037, 1, 1)))
    }

    @Test
    fun `the end sent is the day after the last day entered`() {
        assertEquals(LocalDate.of(2037, 1, 2), endDateAfter(LocalDate.of(2037, 1, 1)))
        assertEquals(LocalDate.of(2037, 3, 1), endDateAfter(LocalDate.of(2037, 2, 28)))
    }

    @Test
    fun `the two are inverse`() {
        val day = LocalDate.of(2037, 1, 26)
        assertEquals(day, lastDayOf(endDateAfter(day)))
    }

    @Test
    fun `an event without an end date lasts its start day`() {
        assertEquals(LocalDate.of(2037, 1, 2), Event(id = "e", summary = "e", allDay = true, startDate = "2037-01-01").endDateExclusive)
    }
}

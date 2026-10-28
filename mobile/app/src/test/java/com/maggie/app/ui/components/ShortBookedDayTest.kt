package com.maggie.app.ui.components

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The short day of a list row (MAG-634): « 1 oct. », with the year only when it is not this one. */
class ShortBookedDayTest {

    private val today = LocalDate.of(2026, 10, 28)

    @Test
    fun `a day of this year is the day and the abbreviated month`() {
        assertEquals("1 oct.", formatShortBookedDay("2026-10-01", today))
        assertEquals("1 oct.", formatShortBookedDay("2026-10-01T00:00:00+00:00", today))
        assertEquals("15 janv.", formatShortBookedDay("2026-01-15", today))
    }

    @Test
    fun `a day of another year keeps its year`() {
        assertEquals("30 déc. 2025", formatShortBookedDay("2025-12-30", today))
    }

    @Test
    fun `no date gives nothing, an unreadable one is given back as it came`() {
        assertNull(formatShortBookedDay(null, today))
        assertEquals("bientôt", formatShortBookedDay("bientôt", today))
    }
}

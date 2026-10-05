package com.maggie.app.ui.screens.fullcalendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class MonthChangeTargetTest {

    @Test
    fun `the month already shown does not move the date`() {
        // The month grid reports the month it opens on; that must not snap
        // Monday 2026-10-05 back to 2026-10-01 (MAG-234).
        assertNull(monthChangeTarget(YearMonth.parse("2026-10"), LocalDate.parse("2026-10-05")))
    }

    @Test
    fun `scrolling to another month goes to its first day`() {
        assertEquals(
            LocalDate.parse("2026-11-01"),
            monthChangeTarget(YearMonth.parse("2026-11"), LocalDate.parse("2026-10-05")),
        )
    }
}

package com.maggie.app.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class DateRangesTest {

    private fun at(instant: String): Clock = Clock.fixed(Instant.parse(instant), ZoneId.of("UTC"))

    @Test
    fun `today is the Paris day when the device zone is still on the day before`() {
        // 2026-10-04 22:50 UTC is Monday 2026-10-05 00:50 in Paris (MAG-234).
        assertEquals(LocalDate.parse("2026-10-05"), DateRanges.todayDate(at("2026-10-04T22:50:00Z")))
    }

    @Test
    fun `today stays on the Paris day just before midnight`() {
        // 2026-10-04 21:50 UTC is Sunday 2026-10-04 23:50 in Paris.
        assertEquals(LocalDate.parse("2026-10-04"), DateRanges.todayDate(at("2026-10-04T21:50:00Z")))
    }

    @Test
    fun `today follows the winter offset after the clocks go back`() {
        // 2026-10-25 01:30 UTC is 02:30 CET, the second 02:30 of the night.
        assertEquals(LocalDate.parse("2026-10-25"), DateRanges.todayDate(at("2026-10-25T01:30:00Z")))
    }

    @Test
    fun `the time of day is read in Paris`() {
        assertEquals(LocalTime.parse("00:50"), DateRanges.nowTime(at("2026-10-04T22:50:00Z")))
    }
}

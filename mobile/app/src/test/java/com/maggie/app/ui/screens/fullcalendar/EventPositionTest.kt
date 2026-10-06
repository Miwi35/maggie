package com.maggie.app.ui.screens.fullcalendar

import androidx.compose.ui.unit.Dp
import com.maggie.app.data.model.ExpandedEvent
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * The block of a multi-day event is drawn from the part of the event that falls
 * inside the day shown, on the 07:00-24:00 grid (MAG-207).
 */
class EventPositionTest {

    private val zone = ZoneId.of("Europe/Paris")
    private val departure = LocalDate.parse("2026-10-09")
    private val arrival = departure.plusDays(1)

    private fun event(start: String, end: String) = ExpandedEvent(
        id = "e",
        summary = "Train de nuit pour Vienne",
        startAt = java.time.LocalDateTime.parse(start).atZone(zone).toInstant().toString(),
        endAt = java.time.LocalDateTime.parse(end).atZone(zone).toInstant().toString(),
    )

    private fun hours(dp: Dp) = dp.value / HOUR_HEIGHT.value

    @Test
    fun `an event on a single day keeps its own times`() {
        val (top, height) = calculateEventPosition(event("2026-10-09T10:00", "2026-10-09T11:30"), departure, zone)

        assertEquals(3f, hours(top), 0.001f)
        assertEquals(1.5f, hours(height), 0.001f)
    }

    @Test
    fun `the day it leaves is drawn from the start to midnight`() {
        val train = event("2026-10-09T21:00", "2026-10-10T08:00")

        val (top, height) = calculateEventPosition(train, departure, zone)

        assertEquals(14f, hours(top), 0.001f)
        assertEquals(3f, hours(height), 0.001f)
    }

    @Test
    fun `the day it arrives is drawn from the top of the grid to the end`() {
        val train = event("2026-10-09T21:00", "2026-10-10T08:00")

        val (top, height) = calculateEventPosition(train, arrival, zone)

        assertEquals(0f, hours(top), 0.001f)
        assertEquals(1f, hours(height), 0.001f)
    }

    @Test
    fun `a day it crosses entirely fills the grid`() {
        val trip = event("2026-10-08T21:00", "2026-10-10T08:00")

        val (top, height) = calculateEventPosition(trip, departure, zone)

        assertEquals(0f, hours(top), 0.001f)
        assertEquals(TIMELINE_HOURS.size.toFloat(), hours(height), 0.001f)
    }

    @Test
    fun `a day with a clock change still reads in local hours`() {
        val springForward = LocalDate.parse("2026-03-29")

        val (top, height) = calculateEventPosition(event("2026-03-29T10:00", "2026-03-29T11:00"), springForward, zone)

        assertEquals(3f, hours(top), 0.001f)
        assertEquals(1f, hours(height), 0.001f)
    }

    @Test
    fun `an event straddling the top of the grid is cut there`() {
        val (top, height) = calculateEventPosition(event("2026-10-09T06:00", "2026-10-09T08:30"), departure, zone)

        assertEquals(0f, hours(top), 0.001f)
        assertEquals(1.5f, hours(height), 0.001f)
    }

    @Test
    fun `a zero-length event keeps a minimum height`() {
        val (_, height) = calculateEventPosition(event("2026-10-09T10:00", "2026-10-09T10:00"), departure, zone)

        assertEquals(0.25f, hours(height), 0.001f)
    }
}

package com.maggie.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class ChatDateFormatterTest {

    private val zone = ZoneId.of("Europe/Paris")

    @Test
    fun `first message always gets day and time separator`() {
        // 10:00 UTC = 11:00 Paris (CET in February)
        val result = ChatDateFormatter.getTimeSeparatorLabel(null, "2026-02-15T10:00:00Z")
        assertTrue(result != null)
        assertTrue(result!!.contains("11:00"))
    }

    @Test
    fun `different day gets day and time label`() {
        // 14:30 UTC = 15:30 Paris
        val result = ChatDateFormatter.getTimeSeparatorLabel(
            "2026-02-14T10:00:00Z",
            "2026-02-15T14:30:00Z",
        )
        assertTrue(result != null)
        assertTrue(result!!.contains("15:30"))
    }

    @Test
    fun `same day within 15 minutes returns null`() {
        val result = ChatDateFormatter.getTimeSeparatorLabel(
            "2026-02-15T10:00:00Z",
            "2026-02-15T10:10:00Z",
        )
        assertNull(result)
    }

    @Test
    fun `same day more than 15 minutes returns time only`() {
        val result = ChatDateFormatter.getTimeSeparatorLabel(
            "2026-02-15T10:00:00Z",
            "2026-02-15T10:20:00Z",
        )
        assertTrue(result != null)
        // Should be time only (no day label), e.g. "11:20" in Paris time
        assertTrue(result!!.length <= 5) // "HH:MM" format
    }

    @Test
    fun `today label is Aujourd'hui`() {
        val today = LocalDate.now(zone)
        val instant = today.atStartOfDay(zone).plusHours(12).toInstant()
        val label = ChatDateFormatter.formatDayLabel(instant)
        assertEquals("Aujourd'hui", label)
    }

    @Test
    fun `yesterday label is Hier`() {
        val yesterday = LocalDate.now(zone).minusDays(1)
        val instant = yesterday.atStartOfDay(zone).plusHours(12).toInstant()
        val label = ChatDateFormatter.formatDayLabel(instant)
        assertEquals("Hier", label)
    }

    @Test
    fun `parseIso handles valid ISO strings`() {
        val result = ChatDateFormatter.parseIso("2026-02-15T10:00:00Z")
        assertTrue(result != null)
    }

    @Test
    fun `parseIso returns null for invalid strings`() {
        val result = ChatDateFormatter.parseIso("not-a-date")
        assertNull(result)
    }
}

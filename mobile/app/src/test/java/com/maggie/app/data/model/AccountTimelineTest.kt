package com.maggie.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class AccountTimelineTest {

    private val today = LocalDate.of(2026, 10, 16)

    private fun line(
        amount: Int,
        kind: String = "none",
        other: String? = null,
    ) = TimelineLine(
        id = "tx",
        label = "VIR",
        amountCents = amount,
        transferKind = kind,
        counterpartAccount = other?.let { TimelineCounterpartAccount("acc", it) },
    )

    @Test
    fun `an ordinary line has no transfer badge`() {
        assertNull(line(-1000).transferBadge())
    }

    @Test
    fun `the credit leg of an internal transfer reads depuis the other account`() {
        assertEquals("depuis Compte courant", line(5000, "internal", "Compte courant").transferBadge())
    }

    @Test
    fun `the debit leg of an internal transfer reads vers the other account`() {
        assertEquals("vers Livret A", line(-5000, "internal", "Livret A").transferBadge())
    }

    @Test
    fun `a transfer without a known counterpart falls back to the generic badge`() {
        assertEquals(transferBadgeLabel("internal", 5000), line(5000, "internal").transferBadge())
    }

    @Test
    fun `a rejection keeps its own badge`() {
        assertEquals(transferBadgeLabel("rejected", -5000), line(-5000, "rejected", "Livret A").transferBadge())
    }

    @Test
    fun `an increase reads with a plus and a percent sign`() {
        assertEquals("+33 %", RecurringIncrease(percent = 33, previousCents = -90000).label())
    }

    @Test
    fun `a missed occurrence says what was expected and that it is late`() {
        val missed = TimelineMissedOccurrence("rec", "Loyer", "2026-10-05", -90000)
        assertEquals("Loyer attendu le 5 — en retard", missed.lateLabel())
    }

    @Test
    fun `a missed occurrence on the first of the month reads le 1er`() {
        assertEquals("Loyer attendu le 1er — en retard", TimelineMissedOccurrence("rec", "Loyer", "2026-10-01").lateLabel())
    }

    @Test
    fun `days read Aujourd'hui, Hier, then the weekday with the date`() {
        assertEquals("Aujourd'hui", timelineDayTitle("2026-10-16", today))
        assertEquals("Hier", timelineDayTitle("2026-10-15", today))
        assertEquals("Lundi 5 octobre", timelineDayTitle("2026-10-05", today))
        assertEquals("Jeudi 1er octobre", timelineDayTitle("2026-10-01", today))
    }

    @Test
    fun `a day of another year carries the year, an unreadable one comes back as it is`() {
        assertEquals("Mercredi 31 décembre 2025", timelineDayTitle("2025-12-31", today))
        assertEquals("pas une date", timelineDayTitle("pas une date", today))
    }

    @Test
    fun `a month reads in French with its year`() {
        assertEquals("septembre 2026", timelineMonthTitle("2026-09"))
        assertEquals("n/a", timelineMonthTitle("n/a"))
    }
}

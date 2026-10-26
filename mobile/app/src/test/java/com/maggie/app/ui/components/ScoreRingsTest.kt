package com.maggie.app.ui.components

import com.maggie.app.data.model.Rhythm
import com.maggie.app.data.model.RhythmDebit
import com.maggie.app.data.model.RhythmPoint
import com.maggie.app.data.model.formatCents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoreRingsTest {

    // Spent 600 against 1 000 planned, 400 of it expected by now.
    private val rhythm = Rhythm(spentCents = 60000, expectedSpentCents = 40000, plannedCents = 100000)

    @Test
    fun `the arc is the share of the plan already spent`() {
        assertEquals(0.6f, ringFill(rhythm), 0.0001f)
    }

    @Test
    fun `spending more than planned fills the ring and says nothing else`() {
        assertEquals(1f, ringFill(rhythm.copy(spentCents = 250000)), 0f)
        assertEquals(1f, ringFill(rhythm.copy(plannedCents = 0)), 0f)
    }

    @Test
    fun `nothing spent and nothing planned draws no arc`() {
        assertEquals(0f, ringFill(Rhythm()), 0f)
        assertEquals(0f, ringFill(rhythm.copy(spentCents = 0)), 0f)
    }

    @Test
    fun `the tick sits where the expected spending is on the same scale`() {
        assertEquals(0.4f, ringTick(rhythm)!!, 0.0001f)
        // Spent beyond the plan: the scale is the spending.
        assertEquals(0.2f, ringTick(rhythm.copy(spentCents = 200000))!!, 0.0001f)
    }

    @Test
    fun `no tick without a planned debit`() {
        assertNull(ringTick(Rhythm(spentCents = 1349)))
        assertNull(expectedLine(Rhythm(spentCents = 1349)))
    }

    @Test
    fun `the expected line names the amount expected to date, only with a plan`() {
        assertEquals("Attendu ${formatCents(40000)}", expectedLine(rhythm))
    }

    @Test
    fun `a ring reads its amounts and never a verdict`() {
        assertEquals(
            "Mois : ${formatCents(60000)} dépensés, ${formatCents(40000)} attendus. Afficher les raisons",
            ringDescription("Mois", rhythm),
        )
        assertEquals(
            "Semaine : ${formatCents(1349)} dépensés. Afficher les raisons",
            ringDescription("Semaine", Rhythm(spentCents = 1349)),
        )
        assertTrue(formatCents(60000).endsWith("€"))
    }

    @Test
    fun `spending beyond what is expected carries no word of pace`() {
        val description = ringDescription("Mois", rhythm.copy(spentCents = 900000))

        for (word in listOf("rythme", "dépassé", "restant", "reste")) {
            assertTrue(description, !description.contains(word))
        }
    }

    // The curve

    private val curve = listOf(
        RhythmPoint("2026-03-04", spentCents = 0, expectedCents = 0),
        RhythmPoint("2026-03-05", spentCents = 90000, expectedCents = 90000),
        RhythmPoint("2026-03-06", spentCents = 92000, expectedCents = 91000),
        RhythmPoint("2026-03-07", spentCents = null, expectedCents = 92000),
        RhythmPoint("2026-03-08", spentCents = null, expectedCents = 93000),
    )
    private val rent = RhythmDebit(date = "2026-03-05", label = "Loyer", amountCents = 90000)

    @Test
    fun `days are spread from the first to the last`() {
        assertEquals(0f, dayFraction(0, 5), 0f)
        assertEquals(0.5f, dayFraction(2, 5), 0f)
        assertEquals(1f, dayFraction(4, 5), 0f)
        assertEquals(0f, dayFraction(0, 1), 0f)
    }

    @Test
    fun `a debit sits on its date`() {
        assertEquals(0.25f, debitFraction(rent, curve)!!, 0f)
        assertNull(debitFraction(rent.copy(date = "2026-04-01"), curve))
    }

    @Test
    fun `today is the last day with a known spending`() {
        assertEquals("2026-03-06", lastKnownPoint(Rhythm(curve = curve))?.date)
        assertNull(lastKnownPoint(Rhythm(curve = curve.map { it.copy(spentCents = null) })))
    }

    @Test
    fun `the ceiling holds the tallest value and is never zero`() {
        assertEquals(93000, curveCeiling(Rhythm(curve = curve)))
                assertEquals(1, curveCeiling(Rhythm()))
    }

    @Test
    fun `the curve says the two figures it carries, never a verdict`() {
        assertEquals(
            "Dépensé cumulé : ${formatCents(92000)} pour ${formatCents(91000)} attendus",
            curveDescription(Rhythm(curve = curve, debits = listOf(rent))),
        )
    }

    @Test
    fun `a debit marker says its amount and its date`() {
        assertEquals("Loyer, ${formatCents(90000)}, prélevé le 5 mars", debitDescription(rent))
    }

    @Test
    fun `a date that cannot be read is shown as it is`() {
        assertEquals("not-a-date", dayMonthLabel("not-a-date"))
    }

    @Test
    fun `the unclassified line carries the amount, and only when a debit is left unclassified`() {
        assertEquals(
            "${formatCents(200000)} non classés",
            unclassifiedLine(rhythm.copy(unclassifiedCount = 3, unclassifiedCents = 200000)),
        )
        assertNull(unclassifiedLine(rhythm))
    }
}

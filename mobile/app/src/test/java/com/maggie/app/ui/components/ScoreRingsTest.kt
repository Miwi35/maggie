package com.maggie.app.ui.components

import com.maggie.app.data.model.PeriodScore
import com.maggie.app.data.model.Rhythm
import com.maggie.app.data.model.RhythmDebit
import com.maggie.app.data.model.RhythmPoint
import com.maggie.app.data.model.formatCents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoreRingsTest {

    private val onTrack = Rhythm(budgetCents = 20000, remainingCents = 12000, expectedRemainingCents = 10000)

    @Test
    fun `the arc is what remains of the budget`() {
        assertEquals(0.6f, ringFill(onTrack), 0.0001f)
    }

    @Test
    fun `a spent budget leaves an empty arc`() {
        assertEquals(0f, ringFill(onTrack.copy(remainingCents = 0)), 0f)
    }

    @Test
    fun `an overspent period fills the whole ring`() {
        assertEquals(1f, ringFill(onTrack.copy(remainingCents = -500)), 0f)
    }

    @Test
    fun `a remainder above the budget does not draw past the circle`() {
        assertEquals(1f, ringFill(onTrack.copy(remainingCents = 25000)), 0f)
    }

    @Test
    fun `without a budget there is nothing to divide and nothing to draw`() {
        val none = Rhythm(budgetCents = 0, spentCents = 1349, remainingCents = -1349)

        assertEquals(0f, ringFill(none), 0f)
        assertNull(ringTick(none))
        assertTrue(!hasEnvelope(none))
    }

    @Test
    fun `the tick sits where the remainder should be today`() {
        assertEquals(0.5f, ringTick(onTrack)!!, 0.0001f)
    }

    @Test
    fun `an expected remainder below zero puts the tick at the end of the arc`() {
        assertEquals(0f, ringTick(onTrack.copy(expectedRemainingCents = -60000))!!, 0f)
    }

    @Test
    fun `a status is told by a glyph as well as by a colour`() {
        assertEquals(ScoreMark.CHECK, scoreMark("green"))
        assertEquals(ScoreMark.INFO, scoreMark("neutral"))
        assertEquals(ScoreMark.WARNING, scoreMark("orange"))
        assertEquals(ScoreMark.ERROR, scoreMark("red"))
        assertEquals(ScoreMark.INFO, scoreMark("something_new"))
    }

    @Test
    fun `the word is the one the API sent, with a fallback when it sent none`() {
        assertEquals("Hors budget", scoreWord("red", "Hors budget"))
        assertEquals("Dans le vert", scoreWord("green", ""))
        assertEquals("Attention", scoreWord("orange", ""))
        assertEquals("Dépassement", scoreWord("red", ""))
        assertEquals("Dans les clous", scoreWord("neutral", ""))
    }

    @Test
    fun `a ring with a budget says its pace, and its amounts in euros`() {
        val score = PeriodScore(status = "green", label = "Dans le vert", rhythm = onTrack)

        assertEquals(
            "Mois : Dans le rythme, il reste ${formatCents(12000)} sur ${formatCents(20000)}, " +
                "attendu aujourd'hui ${formatCents(10000)}",
            ringDescription("Mois", score),
        )
        assertTrue(formatCents(12000).endsWith("€"))
    }

    @Test
    fun `a ring above its pace says so, whatever the API's colour`() {
        val above = onTrack.copy(status = "above", label = "Au-dessus du rythme")
        val score = PeriodScore(status = "orange", label = "Attention", rhythm = above)

        assertTrue(ringDescription("Mois", score).startsWith("Mois : Au-dessus du rythme, "))
    }

    @Test
    fun `a ring with a budget takes its glyph and colour from the pace, whatever the period's status`() {
        for (status in listOf("neutral", "green", "something_new")) {
            assertEquals("green", ringStatus(PeriodScore(status = status, rhythm = onTrack)))
        }
        assertEquals("orange", ringStatus(PeriodScore(status = "neutral", rhythm = onTrack.copy(status = "above"))))
        assertEquals(ScoreMark.CHECK, scoreMark(ringStatus(PeriodScore(status = "neutral", rhythm = onTrack))))
        assertEquals(
            ScoreMark.WARNING,
            scoreMark(ringStatus(PeriodScore(status = "neutral", rhythm = onTrack.copy(status = "above")))),
        )
    }

    @Test
    fun `a ring without a budget keeps the status of its period`() {
        assertEquals("neutral", ringStatus(PeriodScore(status = "neutral")))
        assertEquals("red", ringStatus(PeriodScore(status = "red")))
    }

    @Test
    fun `the pace word falls back when the API sent no label`() {
        assertEquals("Dans le rythme", rhythmWord(onTrack))
        assertEquals("Au-dessus du rythme", rhythmWord(onTrack.copy(status = "above")))
    }

    @Test
    fun `an overspent ring says by how much`() {
        val score = PeriodScore(status = "red", label = "Dépassement", rhythm = onTrack.copy(remainingCents = -9134))

        assertTrue(ringDescription("Mois", score).contains("dépassé de ${formatCents(9134)}"))
    }

    @Test
    fun `a ring without envelope says so`() {
        val score = PeriodScore(status = "neutral", label = "Dans les clous")

        assertEquals("Semaine : Dans les clous. Aucune enveloppe.", ringDescription("Semaine", score))
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
        assertEquals(150000, curveCeiling(Rhythm(budgetCents = 150000, curve = curve)))
        assertEquals(1, curveCeiling(Rhythm()))
    }

    @Test
    fun `the curve says where the spending stands and which debits it carries`() {
        val description = curveDescription("Mois", Rhythm(curve = curve, debits = listOf(rent)))

        assertEquals(
            "Courbe du rythme, Mois : dépensé ${formatCents(92000)} au 6 mars, " +
                "attendu ${formatCents(91000)}, prélèvements : Loyer le 5 mars",
            description,
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
}

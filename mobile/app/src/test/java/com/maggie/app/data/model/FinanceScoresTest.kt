package com.maggie.app.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FinanceScoresTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun text(code: String, category: String? = null, cents: Int? = null) =
        reasonText(ScoreReason(code = code, categoryName = category, amountCents = cents))

    @Test
    fun `a mandatory overspend says it is mandatory`() {
        assertEquals(
            "Alimentation (obligatoire) dépassée de ${formatCents(5000)}",
            text("mandatory_category_exceeded", "Alimentation", 5000),
        )
    }

    @Test
    fun `plans over budget are phrased as a forecast, not a fact`() {
        assertEquals(
            "Les dépenses planifiées feraient dépasser Voyages de ${formatCents(60000)}",
            text("plans_exceed_category_budget", "Voyages", 60000),
        )
    }

    @Test
    fun `annual envelopes say they are annual`() {
        assertEquals(
            "Assurances (annuelle) dépassée de ${formatCents(1000)}",
            text("annual_category_exceeded", "Assurances", 1000),
        )
        assertEquals(
            "Les dépenses planifiées feraient dépasser Assurances (annuelle) de ${formatCents(2000)}",
            text("plans_exceed_annual_category_budget", "Assurances", 2000),
        )
    }

    @Test
    fun `the year-on-year comparison reads both ways`() {
        assertEquals(
            "${formatCents(20000)} de moins qu'à la même période l'an dernier",
            text("below_last_year", cents = 20000),
        )
        assertEquals(
            "${formatCents(20000)} de plus qu'à la même période l'an dernier",
            text("above_last_year", cents = 20000),
        )
    }

    @Test
    fun `the reason codes the API dropped read as they are`() {
        for (code in listOf("total_budget_exceeded", "week_budget_exceeded", "above_rhythm", "below_rhythm", "on_rhythm", "unclassified_debits")) {
            assertEquals(code, text(code, cents = 1500))
        }
    }

    @Test
    fun `the rhythm keeps the three figures and what no category claims, zero when the API says nothing`() {
        val rhythm = json.decodeFromString<Rhythm>(
            """{"spentCents":600,"expectedSpentCents":400,"plannedCents":1000,"unclassifiedCount":3,"unclassifiedCents":200000}""",
        )

        assertEquals(600, rhythm.spentCents)
        assertEquals(400, rhythm.expectedSpentCents)
        assertEquals(1000, rhythm.plannedCents)
        assertEquals(3, rhythm.unclassifiedCount)
        assertEquals(200000, rhythm.unclassifiedCents)
        assertEquals(0, json.decodeFromString<Rhythm>("{}").unclassifiedCount)
    }

    @Test
    fun `the cushion, the reference salary and a missing budget are phrased`() {
        assertEquals("Matelas incomplet : il manque ${formatCents(300)}", text("cushion_incomplete", cents = 300))
        assertEquals(
            "${formatCents(400)} de plus que le salaire de référence",
            text("spending_above_reference_salary", cents = 400),
        )
        assertEquals("Aucune enveloppe sur cette période : rien à comparer", text("no_budget"))
    }

    @Test
    fun `an unknown code falls back to itself rather than breaking`() {
        assertEquals("something_new", text("something_new"))
    }

    @Test
    fun `a response missing its optional parts still reads, with safe defaults`() {
        val scores = json.decodeFromString<FinanceScores>("""{"date":"2026-03-15","week":{"status":"green"}}""")

        assertEquals("green", scores.week.status)
        assertEquals(0, scores.week.rhythm.plannedCents)
        assertTrue(scores.week.rhythm.curve.isEmpty())
        assertEquals("neutral", scores.month.status)
    }

    @Test
    fun `a future day of the curve has no spending and a debit keeps its operation`() {
        val rhythm = json.decodeFromString<Rhythm>(
            """{"curve":[{"date":"2026-03-14","spentCents":100,"expectedCents":90},{"date":"2026-03-15","spentCents":null,"expectedCents":95}],
               "debits":[{"date":"2026-03-05","label":"Loyer","amountCents":90000,"recurringOperationId":"op-1"}]}""",
        )

        assertEquals(100, rhythm.curve[0].spentCents)
        assertNull(rhythm.curve[1].spentCents)
        assertEquals("op-1", rhythm.debits.single().recurringOperationId)
    }
}

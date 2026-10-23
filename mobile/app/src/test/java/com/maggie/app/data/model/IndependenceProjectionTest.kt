package com.maggie.app.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The date N, the curve and the dated milestones of the independence counter (MAG-549). */
class IndependenceProjectionTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun counter(
        projection: IndependenceProjection,
        isMeasurable: Boolean = true,
        hasPassiveIncomeCategories: Boolean = true,
        isReached: Boolean = false,
    ) = IndependenceCounter(
        coveragePercent = 40,
        isMeasurable = isMeasurable,
        hasPassiveIncomeCategories = hasPassiveIncomeCategories,
        isReached = isReached,
        projection = projection,
    )

    @Test
    fun `the projection decodes with whole and decimal coverages and string-keyed milestones`() {
        val decoded = json.decodeFromString(
            IndependenceCounter.serializer(),
            """
            {"coveragePercent": 40, "projection": {
              "startMonth": "2026-10", "nDate": "2027-01",
              "curve": [{"month": "2026-10", "coverage": 0.4}, {"month": "2027-01", "coverage": 1}],
              "milestones": {"25": "2026-10", "50": "2026-11", "75": null, "100": "2027-01"}
            }}
            """,
        )

        val projection = decoded.projection
        assertEquals("2027-01", projection.nDate)
        assertEquals(listOf(0.4, 1.0), projection.curve.map { it.coverage })
        assertEquals("2026-11", projection.milestones["50"])
        assertTrue(projection.milestones.containsKey("75"))
        assertNull(projection.milestones["75"])
    }

    @Test
    fun `a payload cached before the projection still decodes and says nothing of it`() {
        val decoded = json.decodeFromString(
            IndependenceCounter.serializer(),
            """{"coveragePercent": 80, "isMeasurable": true, "hasPassiveIncomeCategories": true}""",
        )

        assertFalse(decoded.projection.isKnown)
        assertNull(independenceDateN(decoded))
        assertTrue(independenceMilestoneRows(decoded.projection).isEmpty())
    }

    @Test
    fun `a month reads in French`() {
        assertEquals("septembre 2045", independenceMonthLabel("2045-09"))
        assertEquals("not a month", independenceMonthLabel("not a month"))
    }

    @Test
    fun `date N names the month`() {
        val projection = IndependenceProjection(startMonth = "2026-10", nDate = "2045-09")

        assertEquals("Date N · septembre 2045", independenceDateN(counter(projection)))
    }

    @Test
    fun `date N not reached is a plain statement`() {
        val projection = IndependenceProjection(startMonth = "2026-10", nDate = null)

        assertEquals("N non atteinte d'ici 20 ans", independenceDateN(counter(projection)))
    }

    @Test
    fun `already reached says the rentes cover the train de vie`() {
        val projection = IndependenceProjection(startMonth = "2026-10", nDate = "2026-10")

        assertEquals(
            "Les rentes couvrent déjà le train de vie",
            independenceDateN(counter(projection, isReached = true)),
        )
    }

    @Test
    fun `nothing measurable says nothing about N`() {
        val projection = IndependenceProjection(startMonth = "2026-10")

        assertNull(independenceDateN(counter(projection, isMeasurable = false)))
        assertNull(independenceDateN(counter(projection, hasPassiveIncomeCategories = false)))
    }

    @Test
    fun `milestones are dated, reached or not reached within 20 years`() {
        val projection = IndependenceProjection(
            startMonth = "2026-10",
            nDate = null,
            milestones = mapOf("25" to "2026-10", "50" to "2028-03", "75" to null, "100" to null),
        )

        assertEquals(
            listOf("25 % · atteint", "50 % · mars 2028", "75 % · non atteint d'ici 20 ans", "100 % · non atteint d'ici 20 ans"),
            independenceMilestoneRows(projection).map { it.text },
        )
    }

    @Test
    fun `the time remaining before N counts years and months`() {
        fun remaining(nDate: String?) = independenceRemaining(IndependenceProjection(startMonth = "2026-10", nDate = nDate))

        assertEquals("dans 8 mois", remaining("2027-06"))
        assertEquals("dans 1 an", remaining("2027-10"))
        assertEquals("dans 1 an et 1 mois", remaining("2027-11"))
        assertEquals("dans 18 ans et 11 mois", remaining("2045-09"))
        assertNull(remaining("2026-10"))
        assertNull(remaining(null))
    }

    @Test
    fun `the curve is summed up for a screen reader`() {
        val projection = IndependenceProjection(
            startMonth = "2026-10",
            nDate = "2027-01",
            curve = listOf(CoveragePoint("2026-10", 0.4), CoveragePoint("2027-01", 1.0)),
        )

        assertEquals(
            "Taux de couverture du train de vie par les rentes : 40 % en octobre 2026, 100 % en janvier 2027. " +
                "100 % atteint en janvier 2027.",
            independenceCurveDescription(projection),
        )
        assertEquals(
            "Aucune courbe de couverture",
            independenceCurveDescription(IndependenceProjection(startMonth = "2026-10")),
        )
    }
}

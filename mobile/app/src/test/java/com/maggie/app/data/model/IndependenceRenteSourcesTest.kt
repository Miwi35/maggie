package com.maggie.app.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The simulated rents the independence projection counts, and how they read (MAG-558). */
class IndependenceRenteSourcesTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `the rente sources decode with their items, a nullable month and nullable points`() {
        val decoded = json.decodeFromString(
            IndependenceCounter.serializer(),
            """
            {"renteSources": {
              "withdrawalRateBasisPoints": 400,
              "placements": {"count": 1, "monthlyRentCents": 23807, "items": [
                {"placementId": "p1", "name": "PEA", "monthlyRentCents": 23807, "fromMonth": "2036-10", "coveragePoints": 12}
              ]},
              "investments": {"count": 1, "monthlyRentCents": 16000, "items": [
                {"projectId": "i1", "name": "Box à louer", "monthlyRentCents": 16000, "fromMonth": null, "untilMonth": "2040-05", "coveragePoints": null}
              ]}
            }}
            """,
        )

        val sources = decoded.renteSources
        assertEquals(400, sources.withdrawalRateBasisPoints)
        assertEquals("2036-10", sources.placements.items.single().fromMonth)
        assertEquals(12, sources.placements.items.single().coveragePoints)
        assertNull(sources.investments.items.single().fromMonth)
        assertEquals("2040-05", sources.investments.items.single().untilMonth)
        assertNull(sources.investments.items.single().coveragePoints)
    }

    @Test
    fun `a payload cached before the rente sources still decodes and draws nothing`() {
        val decoded = json.decodeFromString(
            IndependenceCounter.serializer(),
            """{"coveragePercent": 80, "isMeasurable": true, "hasPassiveIncomeCategories": true}""",
        )

        assertTrue(decoded.renteSources.isEmpty)
        assertTrue(independenceRenteSourceRows(decoded.renteSources).isEmpty())
        assertNull(independenceRenteSourcesCaption(decoded.renteSources))
    }

    @Test
    fun `a placement paying 71 420 euros of capital reads as about 238 euros a month at term`() {
        val sources = RenteSources(
            placements = PlacementRentes(count = 1, monthlyRentCents = 23807),
        )

        val rows = independenceRenteSourceRows(sources)

        assertEquals(1, rows.size)
        assertEquals(IndependenceRenteSourceKind.PLACEMENTS, rows.single().kind)
        assertEquals("Placements · 1 · ≈ ${formatEuros(23807)} / mois à terme", rows.single().text)
        assertEquals("Placements · 1 · ≈ 238 € / mois à terme", rows.single().text)
    }

    @Test
    fun `both rows are drawn in order, placements then investments`() {
        val sources = RenteSources(
            placements = PlacementRentes(count = 3, monthlyRentCents = 31500),
            investments = InvestmentRentes(count = 2, monthlyRentCents = 16000),
        )

        val rows = independenceRenteSourceRows(sources)

        assertEquals(
            listOf(
                "Placements · 3 · ≈ 315 € / mois à terme",
                "Investissements · 2 · ≈ 160 € / mois de loyer",
            ),
            rows.map { it.text },
        )
    }

    @Test
    fun `a source with a count of zero has no row`() {
        val onlyInvestments = RenteSources(investments = InvestmentRentes(count = 1, monthlyRentCents = 16000))
        val onlyPlacements = RenteSources(placements = PlacementRentes(count = 1, monthlyRentCents = 100))

        assertEquals(listOf(IndependenceRenteSourceKind.INVESTMENTS), independenceRenteSourceRows(onlyInvestments).map { it.kind })
        assertEquals(listOf(IndependenceRenteSourceKind.PLACEMENTS), independenceRenteSourceRows(onlyPlacements).map { it.kind })
        assertTrue(independenceRenteSourceRows(RenteSources()).isEmpty())
    }

    @Test
    fun `the simulation caption goes with the placements row only`() {
        val withPlacements = RenteSources(placements = PlacementRentes(count = 1, monthlyRentCents = 100))
        val onlyInvestments = RenteSources(investments = InvestmentRentes(count = 1, monthlyRentCents = 100))

        assertEquals("Simulation · retrait de 4 % par an sur le capital placé", independenceRenteSourcesCaption(withPlacements))
        assertNull(independenceRenteSourcesCaption(onlyInvestments))
        assertEquals(
            "Simulation · retrait de 3,5 % par an sur le capital placé",
            independenceRenteSourcesCaption(withPlacements.copy(withdrawalRateBasisPoints = 350)),
        )
    }
}

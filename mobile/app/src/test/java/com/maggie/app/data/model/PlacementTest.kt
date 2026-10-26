package com.maggie.app.data.model

import com.maggie.app.data.api.ApiCollection
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A placement as the API sends it (MAG-555): the recorded collection of `GET /api/finance/placements`,
 * the answer of `/simulation`, and how the screen says the amounts, the rate and the terms.
 */
class PlacementTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun recording(): String {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "api/contract").isDirectory) dir = dir.parentFile
        return File(dir ?: error("api/contract not found"), "api/contract/responses/finance_placements.collection.json").readText()
    }

    @Test
    fun `the recorded collection reads every field of a placement, final included`() {
        val pea = json.decodeFromString(ApiCollection.serializer(Placement.serializer()), recording()).member.single()

        assertEquals("Recorded PEA", pea.name)
        assertEquals(200000, pea.initial)
        assertEquals(15000, pea.deposit)
        assertEquals(PlacementFrequency.MONTHLY, PlacementFrequency.of(pea.frequency))
        assertEquals(600, pea.annualRate)
        assertEquals(20, pea.years)
        assertEquals(7443007L, pea.finalCents)
        assertEquals(3800000L, pea.paid)
        assertEquals(3643007L, pea.interest)
        assertEquals(24810L, pea.rent)
        assertEquals(15000L, pea.monthlyDeposit)
    }

    @Test
    fun `the simulation reads its figures and its curve`() {
        val body = """
            {"final":51000,"paid":50000,"interest":1000,"rent":170,
             "curve":[{"month":0,"capital":50000,"paid":50000},{"month":12,"capital":51500,"paid":50000}]}
        """.trimIndent()

        val simulation = json.decodeFromString(PlacementSimulation.serializer(), body)

        assertEquals(51000L, simulation.finalCents)
        assertEquals(170L, simulation.rent)
        assertEquals(listOf(0, 12), simulation.curve.map { it.month })
        assertEquals(51500L, simulation.curve[1].capital)
        assertEquals(50000L, simulation.curve[1].paid)
    }

    @Test
    fun `an answer without withdrawal keeps the historical figures for both curves (MAG-556)`() {
        val body = """
            {"final":51000,"paid":50000,"interest":1000,"rent":170,
             "curve":[{"month":0,"capital":50000,"paid":50000},{"month":12,"capital":51500,"paid":50000}]}
        """.trimIndent()

        val simulation = json.decodeFromString(PlacementSimulation.serializer(), body)

        assertFalse(simulation.hasWithdrawals)
        assertEquals(emptyList<PlacementWithdrawal>(), simulation.withdrawals)
        assertEquals(simulation.curve, simulation.curveWithout)
        assertEquals(simulation.curve, simulation.curveWith)
        assertEquals(0L, simulation.gapAtEnd)
        assertEquals(0L, simulation.withdrawn)
        assertEquals(51000L, simulation.finalWith)
        assertEquals(170L, simulation.rentWith)
    }

    @Test
    fun `the simulation reads the withdrawals, both curves, the gap and the figures after them (MAG-556)`() {
        val body = """
            {"final":51000,"paid":50000,"interest":1000,"rent":170,
             "curve":[{"month":0,"capital":50000,"paid":50000},{"month":12,"capital":51500,"paid":50000}],
             "withdrawals":[{"projectId":"p1","name":"Voyage au Japon","month":"2027-04","amountCents":150000}],
             "withdrawn":150000,
             "curveWithout":[{"month":0,"capital":50000,"paid":50000},{"month":12,"capital":51500,"paid":50000}],
             "curveWith":[{"month":0,"capital":50000,"paid":50000},{"month":12,"capital":49000,"paid":50000}],
             "gapAtEnd":2500,"finalWith":48500,"rentWith":161,"name":"PEA","years":1}
        """.trimIndent()

        val simulation = json.decodeFromString(PlacementSimulation.serializer(), body)

        assertTrue(simulation.hasWithdrawals)
        assertEquals(PlacementWithdrawal("p1", "Voyage au Japon", "2027-04", 150000), simulation.withdrawals.single())
        assertEquals("Voyage au Japon", simulation.withdrawalProjects)
        assertEquals(150000L, simulation.withdrawn)
        assertEquals(51500L, simulation.curveWithout[1].capital)
        assertEquals(49000L, simulation.curveWith[1].capital)
        assertEquals(2500L, simulation.gapAtEnd)
        assertEquals(48500L, simulation.finalWith)
        assertEquals(161L, simulation.rentWith)
    }

    @Test
    fun `projects that leave several withdrawals are named once each, in the order bought`() {
        val simulation = PlacementSimulation(
            withdrawals = listOf(
                PlacementWithdrawal("p1", "Voyage au Japon", "2027-04", 100),
                PlacementWithdrawal("p2", "Voiture", "2028-01", 100),
                PlacementWithdrawal("p1", "Voyage au Japon", "2029-01", 100),
            ),
        )

        assertEquals("Voyage au Japon, Voiture", simulation.withdrawalProjects)
    }

    @Test
    fun `amounts are whole euros grouped by thousands, rates in French`() {
        assertEquals("74\u202F430\u00A0€", formatCentsRounded(7443007))
        assertEquals("248\u00A0€", formatCentsRounded(24810))
        assertEquals("0\u00A0€", formatCentsRounded(0))
        assertEquals("6\u00A0%", formatPlacementRate(600))
        assertEquals("3,5\u00A0%", formatPlacementRate(350))
        assertEquals("3,25\u00A0%", formatPlacementRate(325))
        assertEquals("1 an", yearsLabel(1))
        assertEquals("20 ans", yearsLabel(20))
    }

    @Test
    fun `the terms say the deposit and its rhythm, or for a single payment everything paid once`() {
        val pea = Placement(id = "a", name = "PEA", initial = 200000, deposit = 15000, frequency = "monthly", annualRate = 600, years = 20)
        val life = Placement(id = "b", name = "Assurance-vie", deposit = 120000, frequency = "yearly", annualRate = 350, years = 15)
        val quarter = Placement(id = "c", name = "CTO", deposit = 30000, frequency = "quarterly", annualRate = 500, years = 1)
        val book = Placement(id = "d", name = "Livret jeune de Léo", initial = 50000, frequency = "once", annualRate = 300, years = 10)

        assertEquals("150\u00A0€ par mois · 6\u00A0% · 20 ans", placementTerms(pea))
        assertEquals("1\u202F200\u00A0€ par an · 3,5\u00A0% · 15 ans", placementTerms(life))
        assertEquals("300\u00A0€ par trimestre · 5\u00A0% · 1 an", placementTerms(quarter))
        assertEquals("500\u00A0€ une fois · 3\u00A0% · 10 ans", placementTerms(book))
    }
}

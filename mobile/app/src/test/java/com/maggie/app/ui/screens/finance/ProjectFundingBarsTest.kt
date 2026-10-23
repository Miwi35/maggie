package com.maggie.app.ui.screens.finance

import com.maggie.app.data.model.PlanningMonth
import com.maggie.app.data.model.ProjectPlanning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the funding chart draws (MAG-528), month by month, from the `months[]` of the planning
 * (MAG-526): « Mariage 6 000 € à date fixe M2, capacité 500 €/mois » is 2 000 € of salary over a
 * 500 € capacity in months 0 to 2, and a project bought with 800 € of savings shows them that month.
 */
class ProjectFundingBarsTest {

    private fun planning(vararg months: PlanningMonth, isIncomeKnown: Boolean = true) =
        ProjectPlanning(startMonth = "2026-10", isIncomeKnown = isIncomeKnown, months = months.toList())

    private fun month(month: String, salary: Int = 0, savings: Int = 0, capital: Int = 0, capacity: Int = 50000, over: Boolean = false) =
        PlanningMonth(month, capacity, salary, savings, capital, over)

    private val wedding = planning(
        month("2026-10", salary = 200000, over = true),
        month("2026-11", salary = 200000, over = true),
        month("2026-12", salary = 200000, over = true),
        month("2027-01"),
    )

    @Test
    fun `the wedding flags its three months over a 500 euro capacity and reads the excess`() {
        val bars = fundingBars(wedding)

        assertEquals(listOf(true, true, true, false), bars.map { it.isOverCapacity })
        assertEquals(150000, bars[0].excessCents)
        assertEquals(0, bars[3].excessCents)
        assertTrue(bars[0].detail.contains("oct. 2026"))
        assertTrue(bars[0].detail.contains("Dépassement"))
        assertFalse(bars[3].detail.contains("Dépassement"))
    }

    @Test
    fun `800 euros of savings are a savings segment in the month of the purchase`() {
        val bars = fundingBars(planning(month("2026-10"), month("2026-11", savings = 80000)))

        assertEquals(80000, bars[1].cents(FundingSource.SAVINGS))
        assertEquals(0, bars[1].cents(FundingSource.SALARY))
        assertEquals(0, bars[0].cents(FundingSource.SAVINGS))
    }

    @Test
    fun `a bar carries the three sources and its detail names each of them and the capacity`() {
        val bar = fundingBars(planning(month("2026-10", salary = 10000, savings = 20000, capital = 30000))).single()

        assertEquals(listOf(10000, 20000, 30000), FundingSource.entries.map { bar.cents(it) })
        listOf("Salaire", "Économies", "Capital libéré", "Capacité").forEach { assertTrue(it, bar.detail.contains(it)) }
    }

    @Test
    fun `the scale tops out at the tallest stack or capacity, and is never zero`() {
        assertEquals(200000, fundingScale(fundingBars(wedding)))
        assertEquals(100000, fundingScale(fundingBars(planning(month("2026-10", salary = 10000, capacity = 100000)))))
        assertEquals(1, fundingScale(emptyList()))
    }

    @Test
    fun `nothing to draw without months or without a known income`() {
        assertTrue(fundingBars(planning()).isEmpty())
        assertTrue(fundingBars(planning(month("2026-10"), isIncomeKnown = false)).isEmpty())
    }

    @Test
    fun `the three sources take three different category slots`() {
        assertEquals(listOf(1, 2, 3), FundingSource.entries.map { it.slot })
    }

    @Test
    fun `a month names the rent and the resale it receives only when there are some (MAG-557)`() {
        val rented = PlanningMonth(month = "2026-10", capacityCents = 66000, rentCents = 16000, resaleCents = 1000000)
        val plain = PlanningMonth(month = "2026-11", capacityCents = 50000)

        val bars = fundingBars(planning(rented, plain))

        assertTrue(bars[0].detail.contains("Loyers"))
        assertTrue(bars[0].detail.contains("Revente"))
        assertFalse(bars[1].detail.contains("Loyers"))
        assertFalse(bars[1].detail.contains("Revente"))
    }
}

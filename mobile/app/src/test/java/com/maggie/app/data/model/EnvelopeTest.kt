package com.maggie.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class EnvelopeTest {

    @Test
    fun `budgetPeriodLabel names the month of a monthly envelope`() {
        assertEquals("Juillet 2026", budgetPeriodLabel("monthly", 2026, 7))
    }

    @Test
    fun `budgetPeriodLabel names the year of an annual envelope`() {
        assertEquals("Année 2026", budgetPeriodLabel("annual", 2026, null))
    }

    @Test
    fun `budgetPeriodLabel falls back to the year when a monthly envelope has no month`() {
        assertEquals("Année 2026", budgetPeriodLabel("monthly", 2026, null))
    }

    @Test
    fun `budgetModeLabel translates the two API modes`() {
        assertEquals("Mensuel", budgetModeLabel("monthly"))
        assertEquals("Annuel", budgetModeLabel("annual"))
        assertEquals("unknown", budgetModeLabel("unknown"))
    }

    @Test
    fun `consumedFraction reports the spent share of the budget`() {
        assertEquals(0.5f, consumedFraction(20000, 40000), 0.001f)
    }

    @Test
    fun `consumedFraction clamps an overspent budget to one`() {
        assertEquals(1f, consumedFraction(50000, 40000), 0.001f)
    }

    @Test
    fun `consumedFraction treats a zero budget as full once anything is spent`() {
        assertEquals(1f, consumedFraction(100, 0), 0.001f)
        assertEquals(0f, consumedFraction(0, 0), 0.001f)
    }
}

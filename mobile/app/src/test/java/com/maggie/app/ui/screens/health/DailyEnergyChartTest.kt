package com.maggie.app.ui.screens.health

import com.maggie.app.data.model.DailyEnergy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** The Pas and Énergie cards' arithmetic (MAG-746): the window, the steps, the stack, the figures as written. */
class DailyEnergyChartTest {

    private val today = LocalDate.parse("2026-10-21")
    private val full = DailyEnergy("2026-10-20", steps = 8000, activeKcal = 400, bmrKcal = 1648, workoutKcal = 300, stepsKcal = 14, expendedKcal = 1962)
    private val noBase = DailyEnergy("2026-10-21", steps = 4000, activeKcal = 200, bmrKcal = null, workoutKcal = 100, stepsKcal = 7, expendedKcal = null)

    @Test
    fun `a window has one row per day of the period, the missing ones empty, today last`() {
        val window = energyWindow(listOf(full), SuiviPeriod.WEEK, today)

        assertEquals(7, window.size)
        assertEquals("2026-10-15", window.first().date)
        assertEquals("2026-10-21", window.last().date)
        assertEquals(full, window[5])
        assertNull(window.first().steps)
        assertEquals(0, window.first().workoutKcal)
    }

    @Test
    fun `the year window is 365 days`() {
        assertEquals(365, energyWindow(emptyList(), SuiviPeriod.YEAR, today).size)
    }

    @Test
    fun `a window with no steps has no source`() {
        assertFalse(hasSteps(energyWindow(listOf(DailyEnergy("2026-10-20")), SuiviPeriod.WEEK, today)))
        assertTrue(hasSteps(energyWindow(listOf(full), SuiviPeriod.WEEK, today)))
    }

    @Test
    fun `the average counts only the days that have steps, rounded`() {
        val window = energyWindow(listOf(full, noBase.copy(steps = 4001)), SuiviPeriod.MONTH, today)

        assertEquals(6001, averageSteps(window))
        assertNull(averageSteps(energyWindow(emptyList(), SuiviPeriod.MONTH, today)))
    }

    @Test
    fun `the chosen day is returned, today when none is chosen or the day is out of the window`() {
        val window = energyWindow(listOf(full, noBase), SuiviPeriod.WEEK, today)

        assertEquals(full, selectedEnergy(window, LocalDate.parse("2026-10-20")))
        assertEquals(noBase, selectedEnergy(window, null))
        assertEquals(noBase, selectedEnergy(window, LocalDate.parse("2020-01-01")))
    }

    @Test
    fun `a stack holds base, pas and séances, and what does not exist stacks for nothing`() {
        assertEquals(EnergyParts(1648, 14, 300), energyParts(full))
        assertEquals(EnergyParts(0, 7, 100), energyParts(noBase))
        assertEquals(1962, energyParts(full).height)
        assertEquals(1962, maxStack(listOf(full, noBase)))
        assertEquals(0, maxStack(emptyList()))
    }

    @Test
    fun `figures are grouped by a plain space and a missing total is a dash`() {
        assertEquals("8 000", formatCount(8000))
        assertEquals("950", formatCount(950))
        assertEquals("2 052 kcal", formatKcal(2052))
        assertEquals(DASH, formatKcal(null))
    }
}

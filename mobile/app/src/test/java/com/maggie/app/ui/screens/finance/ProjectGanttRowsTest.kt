package com.maggie.app.ui.screens.finance

import com.maggie.app.data.model.CushionPlanState
import com.maggie.app.data.model.PlannedCushion
import com.maggie.app.data.model.PlannedLoan
import com.maggie.app.data.model.PlannedProject
import com.maggie.app.data.model.ProjectPlanning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the Gantt draws (MAG-527), month by month, from the planning of MAG-526: « Vélo 1 200 € puis
 * Japon 3 000 €, 500 €/mois » is the bike from month 0 to 2 and Japan from 2 to 8.
 */
class ProjectGanttRowsTest {

    private fun project(name: String, position: Int = 0, from: String = "2026-10", purchase: String? = "2026-12", block: PlannedProject.() -> PlannedProject = { this }) =
        PlannedProject(
            projectId = name.lowercase(),
            name = name,
            position = position,
            amountCents = 100000,
            savingFromMonth = from,
            purchaseMonth = purchase,
        ).block()

    private fun planning(
        vararg projects: PlannedProject,
        cushion: PlannedCushion = PlannedCushion(),
        isIncomeKnown: Boolean = true,
    ) = ProjectPlanning(startMonth = "2026-10", isIncomeKnown = isIncomeKnown, cushion = cushion, projects = projects.toList())

    private val bike = project("Vélo", 0, "2026-10", "2026-12")
    private val japan = project("Japon", 1, "2026-12", "2027-06")

    @Test
    fun `the bike runs from month 0 to 2 and Japan from 2 to 8, in that order`() {
        val rows = ganttRows(planning(bike, japan))

        assertEquals(listOf("Vélo", "Japon"), rows.map { it.label })
        assertEquals(0 to 2, rows[0].saving!!.let { it.from to it.to })
        assertEquals(2, rows[0].purchase!!.at)
        assertEquals(2 to 8, rows[1].saving!!.let { it.from to it.to })
        assertEquals(8, rows[1].purchase!!.at)
    }

    @Test
    fun `a project past 36 months runs to the edge with an arrow and no purchase month`() {
        val far = project("Maison", purchase = null) { copy(isBeyondHorizon = true) }

        val row = ganttRows(planning(far)).single()

        assertEquals(35, row.saving!!.to)
        assertTrue(row.saving!!.runsOn)
        assertNull(row.purchase)
    }

    @Test
    fun `a purchase in the last month is still drawn, the next one is not`() {
        val last = project("Dernier", purchase = "2029-09")
        val out = project("Hors", purchase = "2029-10")

        val rows = ganttRows(planning(last, out))

        assertEquals(35, rows[0].purchase!!.at)
        assertNull(rows[1].purchase)
    }

    @Test
    fun `a loan starts the month after the purchase and ends at its last payment`() {
        val car = project("Voiture", purchase = "2027-01") { copy(loan = PlannedLoan("2027-02", "2027-12", 25000)) }

        val loan = ganttRows(planning(car)).single().loan!!

        assertEquals(4 to 14, loan.from to loan.to)
        assertFalse(loan.runsOn)
        assertTrue(loan.detail.contains("emprunt dès févr. 2027 jusqu'en déc. 2027"))
    }

    @Test
    fun `a loan that outlives the 36 months is cut at the edge with an arrow`() {
        val house = project("Maison", purchase = "2027-01") { copy(loan = PlannedLoan("2027-02", null, 90000)) }
        val long = project("Long", purchase = "2027-01") { copy(loan = PlannedLoan("2027-02", "2035-01", 90000)) }

        val rows = ganttRows(planning(house, long))

        assertEquals(35, rows[0].loan!!.to)
        assertTrue(rows[0].loan!!.runsOn)
        assertEquals(35, rows[1].loan!!.to)
        assertTrue(rows[1].loan!!.runsOn)
    }

    @Test
    fun `a fixed date has its padlock, a deadline its mark, an unmet dependency its signal`() {
        val fixed = project("Déménagement", purchase = "2027-03") { copy(dateMode = "fixed", targetOn = "2027-03-01") }
        val deadline = project("Vélo", 1, purchase = "2027-02") { copy(dateMode = "deadline", targetOn = "2027-01-31", unmetDependencies = listOf("p1")) }

        val rows = ganttRows(planning(fixed, deadline))

        assertTrue(rows[0].isFixed)
        assertNull(rows[0].deadline)
        assertFalse(rows[1].isFixed)
        assertEquals(3, rows[1].deadline!!.at)
        assertTrue(rows[1].hasUnmetDependency)
        assertFalse(rows[0].hasUnmetDependency)
    }

    @Test
    fun `a project past its deadline is drawn beyond it, with nothing that blames`() {
        val late = project("Vélo", purchase = "2027-04") {
            copy(dateMode = "deadline", targetOn = "2026-12-31", isDeadlineMissed = true, delayMonths = 4)
        }
        val onTime = project("Vélo", purchase = "2026-12") { copy(dateMode = "deadline", targetOn = "2026-12-31") }

        val lateRow = ganttRows(planning(late)).single()
        val onTimeRow = ganttRows(planning(onTime)).single()

        assertEquals(2, lateRow.deadline!!.at)
        assertEquals(6, lateRow.saving!!.to)
        // The same elements as a project on time: the lateness is the bar's length, not a sign.
        assertEquals(onTimeRow.copy(saving = null, purchase = null, deadline = null), lateRow.copy(saving = null, purchase = null, deadline = null))
        val words = listOfNotNull(lateRow.saving!!.detail, lateRow.purchase?.detail, lateRow.deadline?.detail).joinToString(" ")
        assertFalse(Regex("retard|manqu|dépass|échou|raté|alerte|erreur", RegexOption.IGNORE_CASE).containsMatchIn(words))
    }

    @Test
    fun `a Matelas line leads while the cushion builds, up to the month it is complete`() {
        val rows = ganttRows(planning(bike, cushion = PlannedCushion(CushionPlanState.BUILDING, "2027-02")))

        assertEquals(listOf("Matelas", "Vélo"), rows.map { it.label })
        assertTrue(rows[0].isCushion)
        assertEquals(0 to 4, rows[0].saving!!.let { it.from to it.to })
        assertTrue(rows[0].saving!!.detail.contains("constitué en févr. 2027"))
    }

    @Test
    fun `a recharging cushion that never completes runs to the edge`() {
        val row = ganttRows(planning(bike, cushion = PlannedCushion(CushionPlanState.RECHARGING, null))).first()

        assertEquals(35, row.saving!!.to)
        assertTrue(row.saving!!.runsOn)
    }

    @Test
    fun `a complete cushion has no line`() {
        assertEquals(listOf("Vélo"), ganttRows(planning(bike)).map { it.label })
    }

    @Test
    fun `an unknown income or no project draws nothing`() {
        assertTrue(ganttRows(planning(bike, isIncomeKnown = false)).isEmpty())
        assertTrue(ganttRows(planning()).isEmpty())
    }

    @Test
    fun `the compact variant keeps the first three projects and no Matelas line`() {
        val four = (0..3).map { project("P$it", it) }

        val rows = ganttRows(planning(*four.toTypedArray(), cushion = PlannedCushion(CushionPlanState.BUILDING, "2027-02")), compact = true)

        assertEquals(listOf("P0", "P1", "P2"), rows.map { it.label })
    }

    @Test
    fun `the months read in French`() {
        assertEquals("oct. 2026", ganttMonthLabel("2026-10"))
        assertEquals("juin 2027", ganttMonthLabel("2027-06"))
        assertEquals("janv. 2028", ganttMonthLabel("2028-01"))
    }
}

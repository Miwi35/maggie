package com.maggie.app.ui.screens.finance

import com.maggie.app.data.model.PlannedProject
import com.maggie.app.data.model.Project
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProjectRowLabelsTest {

    private val move = Project(id = "p1", name = "Déménagement", amountCents = 250000, dateMode = "fixed", targetOn = "2027-03-01")

    @Test
    fun `a fixed date reads « Date fixe · 1 mars 2027 »`() {
        assertEquals("Date fixe · 1 mars 2027", projectRowDateLabel(move, listOf(move)))
    }

    @Test
    fun `a deadline reads « Au plus tard · août 2027 »`() {
        val deadline = move.copy(dateMode = "deadline", targetOn = "2027-08-15")
        assertEquals("Au plus tard · août 2027", projectRowDateLabel(deadline, listOf(deadline)))
    }

    @Test
    fun `no date reads « Sans date », and « Sans date · après Déménagement » when it waits for one`() {
        val none = Project(id = "p2", name = "Canapé")
        val waiting = none.copy(dependsOn = listOf("/api/finance/projects/p1"))
        assertEquals("Sans date", projectRowDateLabel(none, listOf(move, none)))
        assertEquals("Sans date · après Déménagement", projectRowDateLabel(waiting, listOf(move, waiting)))
    }

    @Test
    fun `a fixed date is warning, a deadline info, no date neutral`() {
        assertEquals(DatePillTone.WARNING, projectDatePillTone(move))
        assertEquals(DatePillTone.INFO, projectDatePillTone(move.copy(dateMode = "deadline")))
        assertEquals(DatePillTone.NEUTRAL, projectDatePillTone(move.copy(dateMode = "none")))
    }

    @Test
    fun `the purchase month reads « mars 2027 », none past the horizon or without planning`() {
        assertEquals("mars 2027", projectRowMonthLabel(PlannedProject(projectId = "p1", name = "A", purchaseMonth = "2027-03")))
        assertEquals("juin 2027", projectRowMonthLabel(PlannedProject(projectId = "p1", name = "A", purchaseMonth = "2027-06")))
        assertNull(projectRowMonthLabel(PlannedProject(projectId = "p1", name = "A", purchaseMonth = null)))
        assertNull(projectRowMonthLabel(null))
    }

    @Test
    fun `the gathered share is gathered over amount, kept between 0 and 1, none without planning or amount`() {
        fun planned(gathered: Int) = PlannedProject(projectId = "p1", name = "A", gatheredCents = gathered)
        assertEquals(0.2f, projectGatheredShare(planned(50000), 250000)!!, 0.0001f)
        assertEquals(1f, projectGatheredShare(planned(400000), 250000)!!, 0.0001f)
        assertEquals(0f, projectGatheredShare(planned(-10), 250000)!!, 0.0001f)
        assertNull(projectGatheredShare(null, 250000))
        assertNull(projectGatheredShare(planned(100), 0))
    }
}

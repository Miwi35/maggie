package com.maggie.app.ui.screens.finance

import com.maggie.app.data.model.Project
import com.maggie.app.data.model.Scenario
import com.maggie.app.data.model.ScenarioProject
import com.maggie.app.data.model.ScenarioRepayment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The editor's draft (MAG-551): text as typed, projects kept in order, repayments complete or refused. */
class ScenarioDraftTest {

    private val filled = ScenarioDraft(name = "Plan", projectIds = listOf("p1", "p2"))

    @Test
    fun `a name is the only thing required`() {
        assertEquals(setOf(ScenarioField.NAME), ScenarioDraft().errors().keys)
        assertTrue(filled.errors().isEmpty())
        assertNotNull(filled.toRequest())
        assertNull(ScenarioDraft().toRequest())
    }

    @Test
    fun `a saving that is not an amount is refused, an empty one is left to the planning`() {
        assertEquals(setOf(ScenarioField.SAVING), filled.copy(saving = "beaucoup").errors().keys)
        assertTrue(filled.copy(saving = "").errors().isEmpty())
        assertTrue(filled.copy(saving = "300,50").errors().isEmpty())
    }

    @Test
    fun `a repayment needs a loan, a month and an amount or the whole capital`() {
        val partial = RepaymentDraft(key = 1, loanId = "l1", month = "2027-06")
        assertFalse(partial.isComplete)
        assertTrue(partial.copy(total = true).isComplete)
        assertTrue(partial.copy(euros = "5000").isComplete)
        assertFalse(partial.copy(euros = "0").isComplete)
        assertFalse(partial.copy(total = true, month = "2027-13").isComplete)
        assertFalse(partial.copy(total = true, loanId = "").isComplete)
        assertEquals(setOf(ScenarioField.REPAYMENTS), filled.copy(repayments = listOf(partial)).errors().keys)
        assertNull(filled.copy(repayments = listOf(partial)).toRequest())
    }

    @Test
    fun `a kept project goes last, a kept one toggled off leaves`() {
        val draft = ScenarioDraft(name = "Plan", projectIds = listOf("p1")).toggleProject("p2")
        assertEquals(listOf("p1", "p2"), draft.projectIds)
        assertEquals(listOf("p2"), draft.toggleProject("p1").projectIds)
    }

    @Test
    fun `a project moves one place, and stops at the ends`() {
        assertEquals(listOf("p2", "p1"), filled.moveProject("p2", -1).projectIds)
        assertEquals(filled, filled.moveProject("p1", -1))
        assertEquals(filled, filled.moveProject("p2", 1))
        assertEquals(filled, filled.moveProject("unknown", 1))
    }

    @Test
    fun `placements toggle`() {
        assertEquals(listOf("pl1"), filled.togglePlacement("pl1").placementIds)
        assertTrue(filled.togglePlacement("pl1").togglePlacement("pl1").placementIds.isEmpty())
    }

    @Test
    fun `a new scenario starts from every project`() {
        val draft = ScenarioDraft.starting(listOf(Project(id = "p1", name = "A"), Project(id = "p2", name = "B")))

        assertEquals(listOf("p1", "p2"), draft.projectIds)
        assertEquals("", draft.name)
    }

    @Test
    fun `editing a scenario reads it back, leaving out the projects that are gone`() {
        val scenario = Scenario(
            id = "s1",
            name = "Mon plan",
            projects = listOf(ScenarioProject("p2", 1), ScenarioProject("gone", 0), ScenarioProject("p1", 2)),
            repayments = listOf(ScenarioRepayment(id = "r1", loanId = "l1", month = "2027-06", amountCents = null)),
            monthlySaving = 30050,
        )

        val draft = ScenarioDraft.of(scenario, knownProjectIds = listOf("p1", "p2"))

        assertEquals("Mon plan", draft.name)
        assertEquals(listOf("p2", "p1"), draft.projectIds)
        assertEquals("300,50", draft.saving)
        assertTrue(draft.repayments.single().total)
        assertTrue(draft.errors().isEmpty())
    }

    @Test
    fun `a partial repayment sends the effect chosen, a total one sends none`() {
        val partial = RepaymentDraft(key = 1, loanId = "l1", month = "2027-06", euros = "3000", effect = "payment")

        assertEquals("payment", partial.toRequest()!!.effect)
        assertNull(partial.copy(effect = null).toRequest()!!.effect)
        assertNull(partial.copy(total = true).toRequest()!!.effect)
    }

    @Test
    fun `editing a scenario reads the effect of its repayments back`() {
        val scenario = Scenario(
            id = "s1",
            name = "Mon plan",
            repayments = listOf(ScenarioRepayment(id = "r1", loanId = "l1", month = "2027-06", amountCents = 300000, effect = "duration")),
        )

        val draft = ScenarioDraft.of(scenario, knownProjectIds = null)

        assertEquals("duration", draft.repayments.single().effect)
        assertEquals("duration", draft.repayments.single().toRequest()!!.effect)
    }
}

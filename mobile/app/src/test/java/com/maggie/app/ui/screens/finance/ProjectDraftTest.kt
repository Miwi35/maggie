package com.maggie.app.ui.screens.finance

import com.maggie.app.data.model.Project
import com.maggie.app.data.model.ProjectDateMode
import com.maggie.app.data.model.ProjectWellbeing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** The form of a project (MAG-519): the API's rules checked before the request, and what it sends. */
class ProjectDraftTest {

    @Test
    fun `an empty draft names every missing field`() {
        val errors = ProjectDraft().errors()

        assertEquals("Donnez un nom au projet.", errors[ProjectField.NAME])
        assertEquals("Indiquez un montant supérieur à zéro.", errors[ProjectField.AMOUNT])
        assertNull(errors[ProjectField.TARGET_ON])
        assertNull(ProjectDraft().toRequest())
    }

    @Test
    fun `a fixed or limited date needs a date, no date needs none`() {
        val draft = ProjectDraft(name = "Canapé", amount = "1200")

        assertEquals("Choisissez la date du projet.", draft.copy(dateMode = ProjectDateMode.FIXED).errors()[ProjectField.TARGET_ON])
        assertEquals("Choisissez la date limite.", draft.copy(dateMode = ProjectDateMode.DEADLINE).errors()[ProjectField.TARGET_ON])
        assertTrue(draft.errors().isEmpty())
    }

    @Test
    fun `a zero or negative amount is refused, a French decimal is read`() {
        val draft = ProjectDraft(name = "Canapé")

        assertNotNull(draft.copy(amount = "0").errors()[ProjectField.AMOUNT])
        assertNotNull(draft.copy(amount = "-5").errors()[ProjectField.AMOUNT])
        assertEquals(120050, draft.copy(amount = "1 200,50").toRequest()!!.amountCents)
    }

    @Test
    fun `the request carries every field, dependencies as IRIs`() {
        val request = ProjectDraft(
            name = "  Canapé ",
            amount = "1200",
            wellbeing = ProjectWellbeing.COMFORT,
            dateMode = ProjectDateMode.DEADLINE,
            targetOn = LocalDate.of(2027, 1, 15),
            dependsOn = listOf("p1"),
            note = " ",
        ).toRequest()!!

        assertEquals("Canapé", request.name)
        assertEquals(120000, request.amountCents)
        assertEquals("comfort", request.wellbeing)
        assertEquals("deadline", request.dateMode)
        assertEquals("2027-01-15", request.targetOn)
        assertEquals(listOf("/api/finance/projects/p1"), request.dependsOn)
        assertNull(request.note)
    }

    @Test
    fun `going back to no date sends a null date, whatever was picked before`() {
        val request = ProjectDraft(
            name = "Canapé",
            amount = "1200",
            dateMode = ProjectDateMode.NONE,
            targetOn = LocalDate.of(2027, 1, 15),
        ).toRequest()!!

        assertEquals("none", request.dateMode)
        assertNull(request.targetOn)
    }

    @Test
    fun `a stored project fills the form`() {
        val draft = ProjectDraft.of(
            Project(
                id = "p2", name = "Canapé", amountCents = 120050, wellbeing = "high", dateMode = "fixed",
                targetOn = "2027-03-01", dependsOn = listOf("/api/finance/projects/p1"), note = "Velours",
            ),
        )

        assertEquals("Canapé", draft.name)
        assertEquals("1200,50", draft.amount)
        assertEquals(ProjectWellbeing.HIGH, draft.wellbeing)
        assertEquals(ProjectDateMode.FIXED, draft.dateMode)
        assertEquals(LocalDate.of(2027, 3, 1), draft.targetOn)
        assertEquals(listOf("p1"), draft.dependsOn)
        assertEquals("Velours", draft.note)
        assertEquals(120050, draft.toRequest()!!.amountCents)
    }

    @Test
    fun `the form shows no error until a save was tried`() {
        val form = ProjectForm(draft = ProjectDraft())

        assertTrue(form.errors.isEmpty())
        assertEquals(2, form.copy(showErrors = true).errors.size)
    }
}

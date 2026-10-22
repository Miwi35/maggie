package com.maggie.app.ui.screens.finance

import com.maggie.app.data.model.Placement
import com.maggie.app.data.model.PlacementFrequency
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The form of a placement (MAG-555): the API's rules checked before the request, and what it sends. */
class PlacementDraftTest {

    @Test
    fun `an empty draft names what is missing`() {
        val errors = PlacementDraft().errors()

        assertEquals("Donnez un nom au placement.", errors[PlacementField.NAME])
        assertEquals(PlacementViewModel.MESSAGE_NOTHING_PAID, errors[PlacementField.DEPOSIT])
        assertEquals("Indiquez un rendement entre 0 et 100 %.", errors[PlacementField.RATE])
        assertEquals("Indiquez une durée de 1 à 60 ans.", errors[PlacementField.YEARS])
        assertNull(PlacementDraft().toRequest())
    }

    @Test
    fun `a placement with only an initial payment, or only a deposit, is valid`() {
        val draft = PlacementDraft(name = "Livret", rate = "3", years = "10")

        assertTrue(draft.copy(initial = "500").errors().isEmpty())
        assertTrue(draft.copy(deposit = "150").errors().isEmpty())
        assertNotNull(draft.copy(initial = "0", deposit = "0").errors()[PlacementField.DEPOSIT])
    }

    @Test
    fun `a negative amount, a rate above 100 % and a durée out of 1 to 60 are refused`() {
        val draft = PlacementDraft(name = "PEA", initial = "2000", deposit = "150", rate = "6", years = "20")

        assertNotNull(draft.copy(deposit = "-5").errors()[PlacementField.DEPOSIT])
        assertNotNull(draft.copy(initial = "abc").errors()[PlacementField.INITIAL])
        assertNotNull(draft.copy(rate = "101").errors()[PlacementField.RATE])
        assertNotNull(draft.copy(years = "0").errors()[PlacementField.YEARS])
        assertNotNull(draft.copy(years = "61").errors()[PlacementField.YEARS])
    }

    @Test
    fun `the request is in cents and basis points, a French decimal read`() {
        val request = PlacementDraft(
            name = " Assurance-vie ", initial = "", deposit = "1 200,50", frequency = PlacementFrequency.YEARLY, rate = "3,5", years = "15",
        ).toRequest()!!

        assertEquals("Assurance-vie", request.name)
        assertEquals(0, request.initial)
        assertEquals(120050, request.deposit)
        assertEquals("yearly", request.frequency)
        assertEquals(350, request.annualRate)
        assertEquals(15, request.years)
    }

    @Test
    fun `a placement opens in the form as it would be typed`() {
        val draft = PlacementDraft.of(
            Placement(id = "a", name = "PEA", initial = 200000, deposit = 15050, frequency = "quarterly", annualRate = 325, years = 20),
        )

        assertEquals("PEA", draft.name)
        assertEquals("2000", draft.initial)
        assertEquals("150,50", draft.deposit)
        assertEquals(PlacementFrequency.QUARTERLY, draft.frequency)
        assertEquals("3,25", draft.rate)
        assertEquals("20", draft.years)
        assertEquals(15050, draft.toRequest()!!.deposit)
    }
}

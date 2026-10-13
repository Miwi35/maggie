package com.maggie.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReferenceSalaryTest {

    @Test
    fun `each source says where the figure comes from`() {
        assertEquals("déclaré sur le matelas", referenceSalarySourceLabel("declared"))
        assertEquals("attendu (opération récurrente)", referenceSalarySourceLabel("expected"))
        assertEquals("mesuré sur 12 mois", referenceSalarySourceLabel("measured"))
        assertEquals("non renseigné", referenceSalarySourceLabel("unknown"))
    }

    @Test
    fun `a known salary is shown per month`() {
        val reference = ReferenceSalary(monthlyCents = 495000, source = "measured")

        assertTrue(reference.isKnown)
        assertEquals("${formatCents(495000)} / mois", referenceSalaryAmountLabel(reference))
    }

    @Test
    fun `an unknown salary says non renseigné and never an amount`() {
        val reference = ReferenceSalary()

        assertFalse(reference.isKnown)
        assertEquals("non renseigné", referenceSalaryAmountLabel(reference))
    }

    @Test
    fun `a zero amount is not a salary`() {
        val reference = ReferenceSalary(monthlyCents = 0, source = "expected")

        assertFalse(reference.isKnown)
        assertEquals("non renseigné", referenceSalaryAmountLabel(reference))
    }

    @Test
    fun `the review states the share consumed and the figure behind it`() {
        val review = MonthlyReview(
            referenceSalary = ReferenceSalary(monthlyCents = 495000, source = "measured"),
            consumedVsReference = ConsumedVsReference(consumedCents = 247500, percentOfReference = 50),
        )

        assertEquals(
            "50 % du salaire de référence consommés (${formatCents(495000)}, mesuré sur 12 mois)",
            referenceShareLabel(review),
        )
    }

    @Test
    fun `the review says nothing when the reference is unknown`() {
        val review = MonthlyReview(consumedVsReference = ConsumedVsReference(consumedCents = 110000))

        assertNull(referenceShareLabel(review))
    }
}

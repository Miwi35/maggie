package com.maggie.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class SafetyCushionTest {

    @Test
    fun `cushionStateLabel translates the three API states`() {
        assertEquals("Constitution en cours", cushionStateLabel("building"))
        assertEquals("Complet", cushionStateLabel("complete"))
        assertEquals("En recharge", cushionStateLabel("recharging"))
        assertEquals("unknown", cushionStateLabel("unknown"))
    }

    @Test
    fun `rechargePlanLabel says nothing to do when the cushion is full`() {
        val status = CushionStatus(state = "complete", deficitCents = 0)

        assertEquals("Matelas complet", rechargePlanLabel(status))
    }

    @Test
    fun `rechargePlanLabel spells out the monthly effort and its duration`() {
        val status = CushionStatus(
            state = "recharging",
            deficitCents = 300000,
            monthlyRechargeCents = 15000,
            rechargeMonths = 20,
        )

        assertEquals("${formatCents(15000)} par mois pendant 20 mois", rechargePlanLabel(status))
    }

    @Test
    fun `rechargePlanLabel reports the absence of a plan`() {
        val status = CushionStatus(deficitCents = 300000, monthlyRechargeCents = 0)

        assertEquals("Aucun plan de recharge", rechargePlanLabel(status))
    }
}

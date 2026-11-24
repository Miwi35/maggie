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
    fun `cushionDeficitLabel says how much of the deficit is lent to relatives`() {
        val status = CushionStatus(deficitCents = 173500, peerClaimsCents = 53500)

        assertEquals(
            "Il manque ${formatCents(173500)}, dont ${formatCents(53500)} avancés à des proches",
            cushionDeficitLabel(status),
        )
    }

    @Test
    fun `cushionDeficitLabel keeps to the deficit when more is lent than is missing`() {
        val status = CushionStatus(deficitCents = 10000, peerClaimsCents = 53500)

        assertEquals(
            "Il manque ${formatCents(10000)}, dont ${formatCents(10000)} avancés à des proches",
            cushionDeficitLabel(status),
        )
    }

    @Test
    fun `cushionDeficitLabel is silent about relatives when nothing is lent`() {
        assertEquals(
            "Il manque ${formatCents(300000)}",
            cushionDeficitLabel(CushionStatus(deficitCents = 300000)),
        )
    }
}

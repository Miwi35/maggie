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

    @Test
    fun `cushionShareRuleLabel gives everything to the cushion below the threshold`() {
        val status = CushionStatus(deficitCents = 300000, criticalDeficitCents = 20000, criticalCents = 400000)

        assertEquals(
            "Sous le seuil critique (2 mois de dépenses, ${formatCents(400000)}) : toute l'épargne va au matelas.",
            cushionShareRuleLabel(status),
        )
    }

    @Test
    fun `cushionShareRuleLabel gives the share past the threshold, and nothing once full`() {
        assertEquals(
            "Seuil critique atteint : 80 % de l'épargne va au matelas, le reste aux projets.",
            cushionShareRuleLabel(CushionStatus(deficitCents = 300000, cushionSharePercent = 80)),
        )
        assertEquals(
            "Matelas complet : toute l'épargne va aux projets.",
            cushionShareRuleLabel(CushionStatus(state = "complete", deficitCents = 0)),
        )
    }
}

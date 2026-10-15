package com.maggie.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class CategorizationRuleTest {

    @Test
    fun `matchTypeLabel translates the API match types`() {
        assertEquals("Contient", matchTypeLabel("contains"))
        assertEquals("Commence par", matchTypeLabel("starts_with"))
        assertEquals("Égal à", matchTypeLabel("equals"))
        assertEquals("unknown", matchTypeLabel("unknown"))
    }

    @Test
    fun `directionLabel translates the API directions`() {
        assertEquals("Peu importe", directionLabel("any"))
        assertEquals("Dépense", directionLabel("debit"))
        assertEquals("Revenu", directionLabel("credit"))
    }

    @Test
    fun `describeRuleScope is empty when the rule narrows on nothing`() {
        assertEquals("", describeRuleScope("any", null, null))
    }

    @Test
    fun `describeRuleScope names the direction alone`() {
        assertEquals("Dépense", describeRuleScope("debit", null, null))
    }

    @Test
    fun `describeRuleScope renders a bounded range`() {
        val scope = describeRuleScope("debit", 1000, 5000)
        assertEquals("Dépense · entre ${formatCents(1000)} et ${formatCents(5000)}", scope)
    }

    @Test
    fun `describeRuleScope renders open-ended bounds`() {
        assertEquals("à partir de ${formatCents(1500)}", describeRuleScope("any", 1500, null))
        assertEquals("jusqu'à ${formatCents(2000)}", describeRuleScope("any", null, 2000))
    }

    @Test
    fun `a rule with a label reads as its match type and label`() {
        assertEquals("Contient « SNCF »", CategorizationRule(id = "r", labelPattern = "SNCF").headline())
    }

    @Test
    fun `a rule without a label reads as its direction and amount range`() {
        val rule = CategorizationRule(id = "r", labelPattern = null, direction = "credit", minAmountCents = 427500, maxAmountCents = 522500)

        assertEquals("Crédit entre ${formatCents(427500)} et ${formatCents(522500)}", rule.headline())
        assertEquals("Débit à partir de ${formatCents(1000)}", CategorizationRule(id = "r", direction = "debit", minAmountCents = 1000).headline())
    }

    @Test
    fun `amountBoundsAround turns an amount and a tolerance into absolute bounds`() {
        assertEquals(427500 to 522500, amountBoundsAround(475000, 10))
        assertEquals(427500 to 522500, amountBoundsAround(-475000, 10))
        assertEquals(1000 to 1000, amountBoundsAround(1000, 0))
        assertEquals(null, amountBoundsAround(0, 10))
        assertEquals(null, amountBoundsAround(1000, 101))
        assertEquals(null, amountBoundsAround(1000, -1))
    }
}

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
}

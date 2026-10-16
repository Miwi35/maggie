package com.maggie.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TransactionToSortTest {

    @Test
    fun `the counter speaks of the pile, singular and empty included`() {
        assertEquals("Rien à trier", toSortCounter(0))
        assertEquals("1 ligne à trier", toSortCounter(1))
        assertEquals("12 lignes à trier", toSortCounter(12))
    }

    @Test
    fun `a rule that was written is told with the lines it filed`() {
        val rule = SortedRule("rule-1", "CARREFOUR")

        assertEquals("Règle « CARREFOUR » créée", sortedSummary(SortResult(rule = rule, ruleCreated = true)))
        assertEquals(
            "Règle « CARREFOUR » créée · 1 autre ligne classée",
            sortedSummary(SortResult(rule = rule, ruleCreated = true, alsoSorted = 1)),
        )
        assertEquals(
            "Règle « CARREFOUR » · 3 autres lignes classées",
            sortedSummary(SortResult(rule = rule, ruleCreated = false, alsoSorted = 3)),
        )
    }

    @Test
    fun `an answer without a rule says nothing more`() {
        assertNull(sortedSummary(SortResult(success = true, remaining = 4)))
        assertNull(sortedSummary(SortResult(rule = SortedRule("rule-1", "CARREFOUR"), ruleCreated = false)))
    }
}

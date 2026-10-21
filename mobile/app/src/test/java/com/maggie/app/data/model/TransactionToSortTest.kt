package com.maggie.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TransactionToSortTest {

    @Test
    fun `the pill says how many lines wait and what they weigh, and is absent on an empty pile`() {
        assertEquals("À qualifier · 3 · ${formatCents(6520)}", toSortPillLabel(ToSortPile(total = 3, totalCents = 6520)))
        assertNull(toSortPillLabel(ToSortPile(total = 0)))
    }

    @Test
    fun `a line is not judgeable and not rated until the server says so`() {
        val line = TransactionToSort(id = "tx-1", label = "X")

        assertEquals(false, line.judgeable)
        assertEquals(RETROSPECT_UNRATED, line.retrospect)
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

package com.maggie.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class RuleSuggestionTest {

    /**
     * How often, and for how much: the two figures that decide whether a
     * merchant is a habit worth a rule. The amount is read through
     * [formatCents] rather than spelled out, because its separator follows the
     * phone's locale.
     */
    @Test
    fun `a suggestion says how often the merchant came back and for how much`() {
        val suggestion = RuleSuggestion(
            pattern = "LECLERC RENNES",
            occurrences = 3,
            totalCents = -45700,
            direction = "debit",
        )

        assertEquals("3 fois · ${formatCents(-45700)}", suggestionWeight(suggestion))
    }

    @Test
    fun `an acceptance says what it wrote and what it filed`() {
        assertEquals(
            "2 règle(s) créée(s), 7 opération(s) rangée(s).",
            acceptedRulesSummary(
                AcceptRuleSuggestionsResult(success = true, created = 2, categorized = 7),
            ),
        )
    }
}

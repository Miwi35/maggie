package com.maggie.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

/** What the phone's envelope detail counts, and how the tree folds (MAG-418). */
class EnvelopeSpendsTest {

    private fun line(id: String, category: String, parent: String? = null, mode: String = "monthly") =
        BudgetLine(id = id, categoryId = category, categoryName = category, parentEnvelopeId = parent, mode = mode)

    private fun tx(
        id: String,
        bookedAt: String,
        cents: Int = -1000,
        kind: String = "none",
        oneOff: String? = null,
    ) = Transaction(id = id, label = id, amountCents = cents, bookedAt = bookedAt, transferKind = kind, oneOffEnvelope = oneOff)

    @Test
    fun `only the debits of the month count, newest first`() {
        val lines = listOf(
            tx("june", "2026-06-30T23:00:00+00:00"),
            tx("early", "2026-07-02T00:00:00+00:00"),
            tx("late", "2026-07-31T00:00:00+00:00"),
            tx("august", "2026-08-01T00:00:00+00:00"),
        )

        assertEquals(listOf("late", "early"), envelopeSpends(lines, 2026, 7).map { it.id })
    }

    @Test
    fun `credits, transfers and spends attributed to a one-off envelope are left out`() {
        val lines = listOf(
            tx("debit", "2026-07-10T00:00:00+00:00"),
            tx("refund", "2026-07-10T00:00:00+00:00", cents = 500),
            tx("transfer", "2026-07-10T00:00:00+00:00", kind = "internal"),
            tx("hellfest", "2026-07-10T00:00:00+00:00", oneOff = "/api/envelopes/hellfest"),
        )

        assertEquals(listOf("debit"), envelopeSpends(lines, 2026, 7).map { it.id })
    }

    @Test
    fun `an annual envelope counts the whole year`() {
        val lines = listOf(
            tx("jan", "2026-01-05T00:00:00+00:00"),
            tx("dec", "2026-12-31T00:00:00+00:00"),
            tx("last-year", "2025-12-31T00:00:00+00:00"),
        )

        assertEquals(listOf("dec", "jan"), envelopeSpends(lines, 2026, null).map { it.id })
    }

    @Test
    fun `a line with no readable date is left out`() {
        assertEquals(emptyList<String>(), envelopeSpends(listOf(tx("nodate", "")), 2026, 7).map { it.id })
    }

    @Test
    fun `a folded envelope hides everything nested in it, to any depth`() {
        val rows = nestBudgetLines(
            listOf(
                line("a", "loisirs"),
                line("b", "bars", parent = "a"),
                line("c", "obsydian", parent = "b"),
                line("d", "cinema", parent = "a"),
                line("e", "sport"),
            ),
        )

        assertEquals(listOf("a", "e"), visibleBudgetLines(rows, emptySet()).map { it.line.id })
        assertEquals(
            listOf("a", "b", "d", "e"),
            visibleBudgetLines(rows, setOf("monthly:loisirs")).map { it.line.id },
        )
        assertEquals(
            listOf("a", "b", "c", "d", "e"),
            visibleBudgetLines(rows, setOf("monthly:loisirs", "monthly:bars")).map { it.line.id },
        )
    }

    @Test
    fun `only an envelope with envelopes nested in it can fold`() {
        val rows = nestBudgetLines(listOf(line("a", "loisirs"), line("b", "bars", parent = "a")))

        assertEquals(listOf(true, false), rows.map { it.hasChildren })
    }
}

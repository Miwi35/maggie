package com.maggie.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransactionSheetTest {

    private val ordinary = Transaction(id = "t", label = "Supermarché", amountCents = -4599, category = "/api/categories/c1")

    @Test
    fun `an ordinary line shows the six sections in order`() {
        assertEquals(
            listOf("nature", "classement", "recurrence", "partage", "jugement", "note"),
            visibleSheetSections("none").map { it.key },
        )
    }

    @Test
    fun `an internal transfer, an advance, a repayment, a top-up and an incident keep only nature and note`() {
        listOf("internal", "advance", "repayment", "funding", "rejected").forEach { kind ->
            assertEquals(
                kind,
                listOf(TransactionSheetSection.Nature, TransactionSheetSection.Note),
                visibleSheetSections(kind),
            )
        }
    }

    @Test
    fun `an ordinary filed line wears no pill`() {
        assertTrue(transactionPills(ordinary).isEmpty())
    }

    @Test
    fun `an internal transfer wears its nature pill`() {
        assertEquals(
            listOf(TransactionPill(TransactionPillKind.INTERNAL, "Virement interne")),
            transactionPills(ordinary.copy(transferKind = "internal", category = null)),
        )
        assertEquals(
            listOf(TransactionPill(TransactionPillKind.INTERNAL, "Avance d'épargne")),
            transactionPills(ordinary.copy(transferKind = "advance")),
        )
    }

    @Test
    fun `a rejected payment and its credit wear the rejection pill`() {
        assertEquals(
            listOf(TransactionPill(TransactionPillKind.REJECTED, "Rejeté")),
            transactionPills(ordinary.copy(transferKind = "rejected")),
        )
        assertEquals(
            listOf(TransactionPill(TransactionPillKind.REJECTED, "Rejet")),
            transactionPills(ordinary.copy(transferKind = "rejected", amountCents = 4599)),
        )
    }

    @Test
    fun `a line attached to a series wears the series pill, with its name once known`() {
        val attached = ordinary.copy(recurringOperation = "/api/recurring_operations/s1")

        assertEquals(listOf(TransactionPill(TransactionPillKind.SERIES, "Série")), transactionPills(attached))
        assertEquals(listOf(TransactionPill(TransactionPillKind.SERIES, "Série · Loyer")), transactionPills(attached, "Loyer"))
    }

    @Test
    fun `an ordinary line without a category is to sort, a neutral one is not`() {
        assertEquals(
            listOf(TransactionPill(TransactionPillKind.TO_SORT, "À qualifier")),
            transactionPills(ordinary.copy(category = null)),
        )
        assertTrue(transactionPills(ordinary.copy(category = null, transferKind = "funding")).none { it.kind == TransactionPillKind.TO_SORT })
    }

    @Test
    fun `nothing emits the shared pill yet`() {
        val everything = listOf(
            ordinary,
            ordinary.copy(category = null),
            ordinary.copy(recurringOperation = "/api/recurring_operations/s1"),
            ordinary.copy(transferKind = "internal"),
            ordinary.copy(transferKind = "rejected"),
        )

        assertTrue(everything.flatMap { transactionPills(it, "Loyer") }.none { it.kind == TransactionPillKind.SHARED })
    }

    @Test
    fun `pills come in one fixed order`() {
        val line = ordinary.copy(category = null, recurringOperation = "/api/recurring_operations/s1")

        assertEquals(
            listOf(TransactionPillKind.SERIES, TransactionPillKind.TO_SORT),
            transactionPills(line).map { it.kind },
        )
    }

    @Test
    fun `an incident is regularised once the credit that gave it back is known`() {
        val credit = TransferLeg(id = "c", label = "REJET")

        assertEquals("Régularisé", incidentRegularisationLabel(credit))
        assertEquals("Non régularisé", incidentRegularisationLabel(null))
    }

    @Test
    fun `a rule drafted from a line takes the direction of its amount`() {
        assertEquals("debit", ruleDirectionFor(-1))
        assertEquals("credit", ruleDirectionFor(250000))
    }
}

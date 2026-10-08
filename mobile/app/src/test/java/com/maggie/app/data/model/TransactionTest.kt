package com.maggie.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransactionTest {

    @Test
    fun `the sign of the amount decides the nature`() {
        assertEquals(TransactionNature.Income, transactionNature(250000))
        assertEquals(TransactionNature.Expense, transactionNature(-4599))
        assertEquals(TransactionNature.Expense, transactionNature(0))
    }

    @Test
    fun `the badge names the kind of neutral line, and the rejected payment once known`() {
        assertEquals("Virement interne", transferBadgeLabel("internal", -300000))
        assertEquals("Rejeté", transferBadgeLabel("rejected", -6240, "REJET PRLV SEPA"))
        assertEquals("Rejet de PRELEVEMENT EDF", transferBadgeLabel("rejected", 6240, "PRELEVEMENT EDF"))
        assertEquals("Rejet", transferBadgeLabel("rejected", 6240))
        assertEquals(null, transferBadgeLabel("none", -8000))
    }

    @Test
    fun `an amount typed without sign gets the sign of the nature`() {
        assertEquals(4250, signedAmountCents(4250, TransactionNature.Income))
        assertEquals(-4250, signedAmountCents(4250, TransactionNature.Expense))
        assertEquals(-4250, signedAmountCents(-4250, TransactionNature.Expense))
        assertEquals(4250, signedAmountCents(-4250, TransactionNature.Income))
    }

    @Test
    fun `an expense reads Dépensée Engagée Planifiée and À arbitrer`() {
        val labels = transactionStatusCodes(TransactionNature.Expense)
            .map { transactionStatusLabel(it, TransactionNature.Expense) }
        assertEquals(listOf("Dépensée", "Engagée", "Planifiée", "À arbitrer"), labels)
    }

    @Test
    fun `an income reads Reçue Attendue and À arbitrer, with no Engagée`() {
        val labels = transactionStatusCodes(TransactionNature.Income)
            .map { transactionStatusLabel(it, TransactionNature.Income) }
        assertEquals(listOf("Reçue", "Attendue", "À arbitrer"), labels)
    }

    @Test
    fun `a status label follows the sign of the amount`() {
        assertEquals("Reçue", transactionStatusLabel("spent", 250000))
        assertEquals("Dépensée", transactionStatusLabel("spent", -4599))
        assertEquals("Attendue", transactionStatusLabel("planned", 250000))
        assertEquals("Planifiée", transactionStatusLabel("planned", -4599))
    }

    @Test
    fun `a legacy committed income reads as expected`() {
        assertEquals("Attendue", transactionStatusLabel("committed", 250000))
    }

    @Test
    fun `switching to income turns Engagée into Planifiée and keeps the other statuses`() {
        assertEquals("planned", statusForNature("committed", TransactionNature.Income))
        assertEquals("spent", statusForNature("spent", TransactionNature.Income))
        assertEquals("committed", statusForNature("committed", TransactionNature.Expense))
    }

    @Test
    fun `only income categories are offered on an income and none on an expense`() {
        val salary = Category(id = "c1", name = "Salaire", obligation = "income")
        val leisure = Category(id = "c2", name = "Loisirs", obligation = "optional")

        assertTrue(categoryMatchesNature(salary, TransactionNature.Income))
        assertFalse(categoryMatchesNature(leisure, TransactionNature.Income))
        assertTrue(categoryMatchesNature(leisure, TransactionNature.Expense))
        assertFalse(categoryMatchesNature(salary, TransactionNature.Expense))
    }
}

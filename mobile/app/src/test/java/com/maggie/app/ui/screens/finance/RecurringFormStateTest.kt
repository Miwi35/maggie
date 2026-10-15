package com.maggie.app.ui.screens.finance

import com.maggie.app.data.model.Qualification
import com.maggie.app.data.model.QualificationKind
import com.maggie.app.data.model.RecurringOperationRecord
import com.maggie.app.data.model.Transaction
import com.maggie.app.data.model.TransactionNature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecurringFormStateTest {
    private val rent = RecurringOperationRecord(
        id = "rent",
        label = "Loyer",
        counterpartyName = "SCI VERDA",
        category = "/api/categories/cat-1",
        account = "/api/accounts/acc-1",
        anchorOn = "2026-03-05",
        referenceAmountCents = -90000,
        referenceSource = "measured",
    )

    private val late = Transaction(
        id = "tx-1",
        label = "LOYER SCI VERDA",
        amountCents = -90000,
        bookedAt = "2026-04-13T00:00:00+00:00",
        account = "/api/accounts/acc-1",
        category = "/api/categories/cat-1",
        counterpartyName = "SCI VERDA",
    )

    @Test
    fun `parseCents reads a French or an English amount and refuses text`() {
        assertEquals(90000, parseCents("900,00"))
        assertEquals(1349, parseCents(" 13.49 "))
        assertEquals(120000, parseCents("1 200"))
        assertNull(parseCents("abc"))
        assertNull(parseCents(""))
    }

    @Test
    fun `a form refuses a save until the required fields are there`() {
        val form = RecurringFormState(RecurringEditing())

        assertNull(form.labelError)
        form.showErrors = true

        assertNull(form.toRequest())
        assertEquals("Donnez un nom à l'opération récurrente.", form.labelError)
        assertEquals("Choisissez un compte.", form.accountError)
        assertEquals("Choisissez une catégorie.", form.categoryError)
        assertEquals("Indiquez un montant différent de zéro.", form.amountError)
    }

    @Test
    fun `a series with neither payee nor pattern is accepted, the signed amount recognises it`() {
        val form = RecurringFormState(RecurringEditing(record = rent))
        form.counterpartyName = ""
        form.labelPattern = ""
        form.showErrors = true

        val request = form.toRequest()!!

        assertNull(request.counterpartyName)
        assertNull(request.labelPattern)
        assertEquals(-90000, request.referenceAmountCents)
    }

    @Test
    fun `without an identity a zero or missing amount is still refused`() {
        val form = RecurringFormState(RecurringEditing(record = rent))
        form.counterpartyName = ""
        form.labelPattern = ""
        form.showErrors = true

        form.amount = "0"
        assertNull(form.toRequest())
        assertEquals("Indiquez un montant différent de zéro.", form.amountError)
        assertNull(form.recognitionRequest())

        form.amount = ""
        assertNull(form.toRequest())
    }

    @Test
    fun `a credit of 4750 euros within 10 percent is asked as such, with no identity`() {
        val form = RecurringFormState(RecurringEditing())
        form.selectNature(TransactionNature.Income)
        form.amount = "4 750"
        form.amountTolerance = "10"

        val asked = form.recognitionRequest()!!

        assertEquals(475000, asked.referenceAmountCents)
        assertEquals(10, asked.amountTolerancePercent)
        assertNull(asked.counterpartyName)
        assertNull(asked.labelPattern)
        assertNull(asked.accountId)
    }

    @Test
    fun `the recognition request carries the identity and the account when they are there`() {
        val form = RecurringFormState(RecurringEditing(record = rent))

        val asked = form.recognitionRequest()!!

        assertEquals("SCI VERDA", asked.counterpartyName)
        assertEquals("acc-1", asked.accountId)
        assertEquals(-90000, asked.referenceAmountCents)
    }

    @Test
    fun `no recognition is asked while the tolerance cannot be read`() {
        val form = RecurringFormState(RecurringEditing(record = rent))

        form.amountTolerance = "101"
        assertNull(form.recognitionRequest())
        form.amountTolerance = "abc"
        assertNull(form.recognitionRequest())
    }

    @Test
    fun `an amount of zero and out-of-range tolerances are refused`() {
        val form = RecurringFormState(RecurringEditing(record = rent))

        form.amount = "0"
        assertNull(form.toRequest())
        form.amount = "900"
        form.amountTolerance = "101"
        assertNull(form.toRequest())
        form.amountTolerance = "20"
        form.dateTolerance = "32"
        assertNull(form.toRequest())
        form.dateTolerance = "31"
        assertNotNull(form.toRequest())
    }

    @Test
    fun `an end before the first due date is refused`() {
        val form = RecurringFormState(RecurringEditing(record = rent))

        form.endsOn = java.time.LocalDate.parse("2026-02-01")
        form.showErrors = true

        assertNull(form.toRequest())
        assertNotNull(form.endsError)
    }

    @Test
    fun `a weekly series has no last day of the month`() {
        val form = RecurringFormState(RecurringEditing(record = rent.copy(dayRule = "last_day_of_month")))

        form.selectPeriod("weekly")

        assertEquals("fixed_day", form.toRequest()!!.dayRule)
    }

    @Test
    fun `changing the nature keeps the category, an income series offers every category`() {
        val form = RecurringFormState(RecurringEditing(record = rent))

        form.selectNature(TransactionNature.Income)

        assertEquals("/api/categories/cat-1", form.category)
        assertEquals(90000, form.toRequest()!!.referenceAmountCents)
    }

    @Test
    fun `the qualification of the series goes with the category in the request`() {
        val form = RecurringFormState(RecurringEditing(record = rent))
        form.qualification = Qualification(mandatory = true, kind = QualificationKind.Loan)

        val request = form.toRequest()!!

        assertEquals("/api/categories/cat-1", request.category)
        assertEquals(Qualification(true, QualificationKind.Loan), request.qualification)
    }

    @Test
    fun `a form starts from the qualification of the series, then of the line it is drawn from`() {
        val stored = rent.copy(qualification = Qualification(true, QualificationKind.Repayment))
        assertEquals(Qualification(true, QualificationKind.Repayment), RecurringFormState(RecurringEditing(record = stored)).qualification)

        val drawn = late.copy(qualification = Qualification(false, QualificationKind.Saving))
        assertEquals(Qualification(false, QualificationKind.Saving), RecurringFormState(RecurringEditing(transaction = drawn)).qualification)

        assertEquals(Qualification.NONE, RecurringFormState(RecurringEditing()).qualification)
    }

    @Test
    fun `saving requalifies only when the category or the qualification moved`() {
        val form = RecurringFormState(RecurringEditing(record = rent))
        assertFalse(form.requalifies)

        form.qualification = Qualification(mandatory = true, kind = QualificationKind.None)
        assertTrue(form.requalifies)

        form.qualification = Qualification.NONE
        form.category = "/api/categories/cat-2"
        assertTrue(form.requalifies)

        assertFalse(RecurringFormState(RecurringEditing()).requalifies)
    }

    @Test
    fun `an expense is written negative and an income positive`() {
        val form = RecurringFormState(RecurringEditing(record = rent))
        form.amount = "950,50"

        assertEquals(-95050, form.toRequest()!!.referenceAmountCents)

        form.selectNature(TransactionNature.Income)
        form.category = "/api/categories/cat-2"

        assertEquals(95050, form.toRequest()!!.referenceAmountCents)
    }

    @Test
    fun `editing keeps the source of an amount left alone and declares one that changed`() {
        val unchanged = RecurringFormState(RecurringEditing(record = rent))
        assertEquals("measured", unchanged.toRequest()!!.referenceSource)

        val changed = RecurringFormState(RecurringEditing(record = rent))
        changed.amount = "950"
        assertEquals("declared", changed.toRequest()!!.referenceSource)
    }

    @Test
    fun `drawn from a transaction the amount is measured, until the owner types another`() {
        val form = RecurringFormState(RecurringEditing(transaction = late))
        form.counterpartyName = "SCI VERDA"

        val request = form.toRequest()!!

        assertEquals("measured", request.referenceSource)
        assertEquals(-90000, request.referenceAmountCents)
        assertEquals("2026-04-13", request.anchorOn)
        assertEquals("SCI VERDA", request.counterpartyName)
        assertEquals("/api/accounts/acc-1", request.account)
        assertEquals("/api/categories/cat-1", request.category)

        form.amount = "910"
        assertEquals("declared", form.toRequest()!!.referenceSource)
    }

    @Test
    fun `a transaction without a payee is recognised by its label`() {
        val form = RecurringFormState(RecurringEditing(transaction = late.copy(counterpartyName = null)))

        val request = form.toRequest()!!

        assertNull(request.counterpartyName)
        assertEquals("LOYER SCI VERDA", request.labelPattern)
    }

    @Test
    fun `a transaction without a payee opens with its label as the pattern, cleaned of its variable parts`() {
        val form = RecurringFormState(RecurringEditing(transaction = late.copy(label = "CB*4567 SAS JARDIS 13/10", counterpartyName = null)))

        assertEquals("", form.counterpartyName)
        assertEquals("SAS JARDIS", form.labelPattern)
        assertEquals("SAS JARDIS", form.toRequest()!!.labelPattern)
    }

    @Test
    fun `a transaction with a payee fills the payee and leaves the pattern empty`() {
        val form = RecurringFormState(RecurringEditing(transaction = late))

        assertEquals("SCI VERDA", form.counterpartyName)
        assertEquals("", form.labelPattern)
        assertNull(form.toRequest()!!.labelPattern)
    }

    @Test
    fun `a transaction without a payee may drop its pattern too and keep only the amount`() {
        val form = RecurringFormState(RecurringEditing(transaction = late.copy(counterpartyName = null)))
        form.labelPattern = ""
        form.showErrors = true

        val request = form.toRequest()!!

        assertNull(request.counterpartyName)
        assertNull(request.labelPattern)
    }

    @Test
    fun `labelPatternFrom drops dates, card and reference numbers and keeps a bare label whole`() {
        assertEquals("Sas Jardis", labelPatternFrom("  Sas   Jardis "))
        assertEquals("PRLV FLIXO", labelPatternFrom("PRLV FLIXO 12/10/2026"))
        assertEquals("PRLV FLIXO", labelPatternFrom("PRLV FLIXO 2026-10-12"))
        assertEquals("BOULANGERIE DUPONT", labelPatternFrom("CARTE X1234 BOULANGERIE DUPONT"))
        assertEquals("VIR SCI VERDA", labelPatternFrom("VIR SCI VERDA REF: 8842001"))
        assertEquals("PRLV EDF", labelPatternFrom("PRLV EDF N° 99812"))
        assertEquals("FLIXO", labelPatternFrom("FLIXO 0123456789"))
        assertEquals("SAS JARDIS", labelPatternFrom("CARTE X9633 SAS JARDIS 13/10"))
        assertEquals("FLIXO", labelPatternFrom("PRLV 12/10 FLIXO"))
        assertEquals("LOYER MARS", labelPatternFrom("VIR 0012345678 LOYER MARS RÉF: 88"))
        assertEquals("12/10/2026 0123456789", labelPatternFrom("12/10/2026 0123456789"))
    }

    @Test
    fun `a transaction on the last day of the month proposes the last day rule`() {
        val form = RecurringFormState(RecurringEditing(transaction = late.copy(bookedAt = "2026-04-30T00:00:00+00:00")))

        assertEquals("last_day_of_month", form.dayRule)
    }
}

package com.maggie.app.ui.screens.finance

import com.maggie.app.data.model.Loan
import com.maggie.app.data.model.LoanLabels
import com.maggie.app.data.model.LoanStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LoanTermsDraftTest {

    private val loan = Loan(
        id = "l1",
        name = "Prêt immo",
        monthlyPaymentCents = 62500,
        annualRateBasisPoints = 180,
        durationMonths = 240,
        insuranceMonthlyCents = 3500,
        prepaymentPenaltyMode = LoanLabels.FIXED,
        prepaymentPenaltyFixedCents = 50000,
    )

    @Test
    fun `a draft shows euros and percents, not cents and basis points`() {
        val draft = LoanTermsDraft.of(loan)

        assertEquals("1,80", draft.rate)
        assertEquals("625,00", draft.payment)
        assertEquals("240", draft.duration)
        assertEquals("35,00", draft.insuranceMonthly)
        assertEquals("500,00", draft.penaltyFixed)
        assertEquals("", draft.fees)
    }

    @Test
    fun `an untouched draft gives back the conditions of the loan`() {
        val request = LoanTermsDraft.of(loan).toRequest()!!

        assertEquals(180, request.annualRateBasisPoints)
        assertEquals(62500, request.monthlyPaymentCents)
        assertEquals(240, request.durationMonths)
        assertEquals(3500, request.insuranceMonthlyCents)
        assertEquals(50000, request.prepaymentPenaltyFixedCents)
        assertNull(request.feesCents)
    }

    @Test
    fun `a condition that does not apply to the type is sent empty`() {
        val draft = LoanTermsDraft.of(loan).copy(referenceIndex = "Euribor", margin = "1,2", deferralMonths = "12")

        val request = draft.toRequest()!!

        assertNull(request.referenceIndex)
        assertNull(request.marginBasisPoints)
        assertNull(request.deferralMonths)
    }

    @Test
    fun `a variable loan sends its index and margin`() {
        val draft = LoanTermsDraft.of(loan).copy(type = LoanLabels.AMORTIZING_VARIABLE, referenceIndex = " Euribor 3M ", margin = "1,2")

        val request = draft.toRequest()!!

        assertEquals("Euribor 3M", request.referenceIndex)
        assertEquals(120, request.marginBasisPoints)
    }

    @Test
    fun `a stepped loan sends its steps and drops the blank rows`() {
        val draft = LoanTermsDraft.of(loan).copy(
            type = LoanLabels.STEPPED,
            steps = listOf(LoanStepDraft("1", "500"), LoanStepDraft("13", "700,50"), LoanStepDraft()),
        )

        assertEquals(listOf(LoanStep(1, 50000), LoanStep(13, 70050)), draft.toRequest()!!.steps)
    }

    @Test
    fun `a half filled step is refused`() {
        val draft = LoanTermsDraft.of(loan).copy(type = LoanLabels.STEPPED, steps = listOf(LoanStepDraft("13", "")))

        assertNull(draft.toRequest())
    }

    @Test
    fun `a missing payment or a typed word is not sent`() {
        assertNull(LoanTermsDraft.of(loan).copy(payment = "").toRequest())
        assertNull(LoanTermsDraft.of(loan).copy(duration = "vingt").toRequest())
    }

    @Test
    fun `the fixed penalty is sent only in the fixed mode`() {
        val request = LoanTermsDraft.of(loan).copy(penaltyMode = LoanLabels.LEGAL_CAP).toRequest()!!

        assertNull(request.prepaymentPenaltyFixedCents)
        assertEquals(LoanLabels.LEGAL_CAP, request.prepaymentPenaltyMode)
    }

    @Test
    fun `the merge patch spells out the cleared fields`() {
        val json = LoanTermsDraft.of(loan).toRequest()!!.toJson()

        assertEquals("null", json.getValue("feesCents").toString())
        assertEquals("180", json.getValue("annualRateBasisPoints").toString())
    }

    @Test
    fun `the conditions of a funding source leave out the payment, which the API computes`() {
        val draft = LoanTermsDraft(rate = "4", duration = "24")

        assertNull(draft.toRequest())
        val json = draft.toFundingConditions()!!
        assertEquals("400", json.getValue("annualRateBasisPoints").toString())
        assertEquals("24", json.getValue("durationMonths").toString())
        assertNull(json["monthlyPaymentCents"])
    }

    @Test
    fun `funding conditions that are not numbers are not sent`() {
        assertNull(LoanTermsDraft(rate = "abc", duration = "24").toFundingConditions())
    }
}

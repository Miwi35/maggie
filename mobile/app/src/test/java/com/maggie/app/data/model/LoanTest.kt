package com.maggie.app.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoanTest {

    @Test
    fun `formatRate renders basis points as a percentage`() {
        assertEquals("3,50 %", formatRate(350))
        assertEquals("0,00 %", formatRate(0))
    }

    @Test
    fun `formatLoanMonth names the month the API returns`() {
        assertEquals("Mars 2027", formatLoanMonth("2027-03"))
    }

    @Test
    fun `formatLoanMonth falls back rather than breaking`() {
        assertEquals("—", formatLoanMonth(null))
        assertEquals("nonsense", formatLoanMonth("nonsense"))
    }

    @Test
    fun `formatRemaining counts in months under a year`() {
        assertEquals("7 mois", formatRemaining(7))
    }

    @Test
    fun `formatRemaining counts in years and months beyond`() {
        assertEquals("1 an", formatRemaining(12))
        assertEquals("2 ans et 6 mois", formatRemaining(30))
    }

    @Test
    fun `formatRemaining says so when the loan outlives the horizon`() {
        assertEquals("Au-delà de l'horizon", formatRemaining(null))
    }

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `a loan as the API now sends it keeps its type, its conditions and its steps`() {
        val loan = json.decodeFromString<Loan>(
            """{"id":"l1","name":"Prêt immo","principalRemainingCents":9800000,"monthlyPaymentCents":62500,
            "annualRateBasisPoints":180,"priority":2,"currency":"EUR","type":"stepped","projected":true,
            "initialAmountCents":12000000,"durationMonths":240,"firstPaymentOn":"2020-04-05",
            "insuranceMonthlyCents":3500,"feesCents":90000,"steps":[{"fromMonth":1,"monthlyPaymentCents":50000},
            {"fromMonth":13,"monthlyPaymentCents":70000}],"earlyRepaymentAllowed":true,
            "prepaymentPenaltyMode":"fixed","prepaymentPenaltyFixedCents":50000,"partialRepaymentEffect":"shorten"}""",
        )

        assertEquals("stepped", loan.type)
        assertTrue(loan.projected)
        assertEquals(13, loan.steps!![1].fromMonth)
        assertEquals("fixed", loan.prepaymentPenaltyMode)
        assertNull(loan.referenceIndex)
    }

    @Test
    fun `a loan from before the conditions is a fixed rate loan at the legal cap`() {
        val loan = json.decodeFromString<Loan>("""{"id":"l1","name":"Crédit auto"}""")

        assertEquals(LoanLabels.AMORTIZING_FIXED, loan.type)
        assertEquals(LoanLabels.LEGAL_CAP, loan.prepaymentPenaltyMode)
        assertFalse(loan.projected)
        assertTrue(loan.earlyRepaymentAllowed)
    }

    @Test
    fun `the debt timeline entry carries the type and the insurance`() {
        val entry = json.decodeFromString<LoanSchedule>(
            """{"id":"l1","name":"Prêt","type":"in_fine","insuranceMonthlyCents":1200,"freedOn":"2034-03","totalInterestCents":620000}""",
        )

        assertEquals("in_fine", entry.type)
        assertEquals(1200, entry.insuranceMonthlyCents)
    }

    @Test
    fun `the schedule of a loan reads its months and its end`() {
        val curve = json.decodeFromString<LoanCurve>(
            """{"principalRemainingCents":9800000,"freedOn":"2034-03","monthsRemaining":89,"totalInterestCents":620000,
            "months":[{"month":"2026-11","remainingCents":9740000},{"month":"2026-12","remainingCents":9680000}]}""",
        )

        assertEquals("2034-03", curve.freedOn)
        assertEquals(2, curve.months.size)
        assertEquals(9680000, curve.months[1].remainingCents)
    }

    @Test
    fun `a loan that never ends has no end date`() {
        val curve = json.decodeFromString<LoanCurve>("""{"principalRemainingCents":100,"freedOn":null,"monthsRemaining":null,"totalInterestCents":0,"months":[]}""")

        assertNull(curve.freedOn)
        assertEquals("Pas de fin calculée", com.maggie.app.ui.screens.finance.loanEndLabel(curve.freedOn))
    }

    @Test
    fun `an early repayment reads the penalty and the real gain`() {
        val result = json.decodeFromString<EarlyRepayment>(
            """{"amountCents":9800000,"penaltyMode":"legal_cap","penaltyCents":88200,"remainingInterestCents":620000,"realGainCents":531800}""",
        )

        assertEquals(88200, result.penaltyCents)
        assertEquals(531800, result.realGainCents)
    }

    @Test
    fun `the labels are the ones the admin shows`() {
        assertEquals("Amortissable taux fixe", LoanLabels.type("amortizing_fixed"))
        assertEquals("Amortissable taux variable", LoanLabels.type("amortizing_variable"))
        assertEquals("In fine", LoanLabels.type("in_fine"))
        assertEquals("À paliers", LoanLabels.type("stepped"))
        assertEquals("Différé partiel", LoanLabels.type("deferred_partial"))
        assertEquals("Différé total", LoanLabels.type("deferred_total"))
        assertEquals("Prêt relais", LoanLabels.type("bridge"))
        assertEquals("Plafond légal", LoanLabels.penaltyMode("legal_cap"))
        assertEquals("Montant fixe", LoanLabels.penaltyMode("fixed"))
        assertEquals("Aucune IRA", LoanLabels.penaltyMode("exempt"))
        assertEquals("Crédit à la consommation", LoanLabels.penaltyMode("consumer"))
        assertEquals("autre", LoanLabels.type("autre"))
    }

    @Test
    fun `the conditions sheet lists only the conditions that are set`() {
        val bare = Loan(id = "l1", name = "Crédit auto", monthlyPaymentCents = 20000, annualRateBasisPoints = 320)
        assertEquals(listOf("Taux annuel", "Mensualité"), bare.conditionRows().map { it.first })

        val rows = bare.copy(
            type = LoanLabels.AMORTIZING_VARIABLE,
            referenceIndex = "Euribor 3M",
            marginBasisPoints = 120,
            rateCapBasisPoints = 450,
            insuranceMonthlyCents = 3500,
            earlyRepaymentAllowed = false,
        ).conditionRows().toMap()

        assertEquals("Euribor 3M", rows[LoanLabels.INDEX])
        assertEquals("1,20 %", rows[LoanLabels.MARGIN])
        assertEquals("4,50 %", rows[LoanLabels.RATE_CAP])
        assertEquals("Non autorisé", rows["Remboursement anticipé"])
        assertFalse(rows.containsKey(LoanLabels.RATE_FLOOR))
    }
}

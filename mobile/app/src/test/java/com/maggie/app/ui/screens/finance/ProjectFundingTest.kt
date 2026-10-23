package com.maggie.app.ui.screens.finance

import com.maggie.app.data.api.ProjectOptionsParams
import com.maggie.app.data.api.projectFundingBody
import com.maggie.app.data.model.Account
import com.maggie.app.data.model.FundingKind
import com.maggie.app.data.model.Project
import com.maggie.app.data.model.ProjectFundingSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The drafts behind the tablet's funding editor and the parameters of the options (MAG-525). */
class ProjectFundingTest {

    private val savings = FundingSourceDraft(FundingKind.SAVINGS, amount = "1000", accountId = "a1")

    @Test
    fun `a source is read from the API in euros and gives back its cents`() {
        val draft = FundingSourceDraft.of(ProjectFundingSource(kind = "savings", amountCents = 150_050, accountId = "a1"))

        assertEquals("1500,50", draft.amount)
        assertEquals(150_050, draft.amountCents)
        assertEquals("a1", draft.toRequest()!!.account)
    }

    @Test
    fun `the plus and minus buttons move the amount by 100 euros and stop at zero`() {
        assertEquals(110_000, savings.stepped(FUNDING_STEP_CENTS).amountCents)
        assertEquals(90_000, savings.stepped(-FUNDING_STEP_CENTS).amountCents)
        assertNull(savings.copy(amount = "50").stepped(-FUNDING_STEP_CENTS).amountCents)
    }

    @Test
    fun `the rest on the salary is the amount minus the sources, never below zero`() {
        assertEquals(900_000, FundingDraft.salaryCents(1_000_000, listOf(savings)))
        assertEquals(0, FundingDraft.salaryCents(50_000, listOf(savings)))
        assertEquals(1_000_000, FundingDraft.salaryCents(1_000_000, emptyList()))
    }

    @Test
    fun `a list that can be sent has no error and each flaw is named`() {
        assertNull(FundingDraft.error(1_000_000, listOf(savings)))
        assertNull(FundingDraft.error(1_000_000, emptyList()))
        assertEquals(
            "Indiquez un montant supérieur à zéro pour chaque source.",
            FundingDraft.error(1_000_000, listOf(savings.copy(amount = ""))),
        )
        assertEquals(
            "Choisissez le compte de chaque source d'économies ou de capital.",
            FundingDraft.error(1_000_000, listOf(savings.copy(accountId = null))),
        )
        assertEquals(
            "Les sources dépassent le montant du projet : baissez-les.",
            FundingDraft.error(50_000, listOf(savings)),
        )
    }

    @Test
    fun `a loan source needs terms or an existing loan, and only one is allowed`() {
        val blank = FundingSourceDraft(FundingKind.LOAN, amount = "6000")
        val typed = blank.copy(loanTerms = LoanTermsDraft(rate = "4", duration = "24"))

        assertEquals("Renseignez le taux et la durée de l'emprunt.", FundingDraft.error(1_000_000, listOf(blank)))
        assertNull(FundingDraft.error(1_000_000, listOf(typed)))
        assertNull(FundingDraft.error(1_000_000, listOf(blank.copy(loanId = "l1"))))
        assertEquals("Un projet n'a qu'un seul emprunt.", FundingDraft.error(1_000_000, listOf(typed, typed.copy(amount = "100"))))
    }

    @Test
    fun `an existing loan source is sent without conditions so the API keeps them`() {
        val request = FundingSourceDraft(FundingKind.LOAN, amount = "6000", loanId = "l1").toRequest()!!

        assertNull(request.loan)
        assertNull(request.account)
        assertEquals(600_000, request.amountCents)
    }

    @Test
    fun `typed conditions of a loan source are sent without the payment`() {
        val request = FundingSourceDraft(FundingKind.LOAN, amount = "6000", loanTerms = LoanTermsDraft(rate = "4", duration = "24"))
            .toRequest()!!

        assertEquals("400", request.loan!!.getValue("annualRateBasisPoints").toString())
        assertNull(request.loan!!["monthlyPaymentCents"])
    }

    @Test
    fun `a capital source keeps the placement it leaves through edits and sends it, a savings source never does (MAG-556)`() {
        val read = FundingSourceDraft.of(
            ProjectFundingSource(kind = "capital", amountCents = 150_000, accountId = "pea-account", placementId = "pea"),
        )

        val edited = read.stepped(FUNDING_STEP_CENTS)

        assertEquals("pea", edited.placementId)
        assertEquals("pea", edited.toRequest()!!.placement)
        assertEquals("pea-account", edited.toRequest()!!.account)
        assertNull(edited.copy(placementId = null).toRequest()!!.placement)
        assertNull(savings.copy(placementId = "pea").toRequest()!!.placement)
    }

    @Test
    fun `the placement is in the body only when the capital names one`() {
        val withPlacement = FundingSourceDraft(FundingKind.CAPITAL, amount = "1500", accountId = "a2", placementId = "pea")
        val without = withPlacement.copy(placementId = null)

        assertEquals(
            """{"sources":[{"kind":"capital","amountCents":150000,"account":"a2","placement":"pea"}]}""",
            projectFundingBody(listOf(withPlacement.toRequest()!!)).toString(),
        )
        assertEquals(
            """{"sources":[{"kind":"capital","amountCents":150000,"account":"a2"}]}""",
            projectFundingBody(listOf(without.toRequest()!!)).toString(),
        )
    }

    @Test
    fun `the body is the whole list, empty to remove every source`() {
        assertEquals("""{"sources":[]}""", projectFundingBody(emptyList()).toString())
        assertEquals(
            """{"sources":[{"kind":"savings","amountCents":100000,"account":"a1"}]}""",
            projectFundingBody(listOf(savings.toRequest()!!)).toString(),
        )
    }

    @Test
    fun `savings come from a savings or checking account outside the cushion, capital from an investment`() {
        val accounts = listOf(
            Account(id = "chk", name = "Courant", type = "checking"),
            Account(id = "sav", name = "Livret", type = "savings"),
            Account(id = "cushion", name = "Matelas", type = "savings", isCushion = true),
            Account(id = "inv", name = "PEA", type = "investment"),
            Account(id = "cash", name = "Espèces", type = "cash"),
        )

        assertEquals(listOf("chk", "sav"), FundingDraft.eligibleAccounts(FundingKind.SAVINGS, accounts).map { it.id })
        assertEquals(listOf("inv"), FundingDraft.eligibleAccounts(FundingKind.CAPITAL, accounts).map { it.id })
        assertEquals(emptyList<Account>(), FundingDraft.eligibleAccounts(FundingKind.LOAN, accounts))
    }

    @Test
    fun `the parameters of the options are percents and euros, blank ones left to the API`() {
        assertEquals(ProjectOptionsParams(yieldBasisPoints = 300), ProjectOptionsDraft().toParams())
        assertEquals(
            ProjectOptionsParams(loanRateBasisPoints = 410, loanMonths = 24, downPaymentCents = 400_000, yieldBasisPoints = 250),
            ProjectOptionsDraft(rate = "4,1", months = "24", downPayment = "4000", yieldRate = "2,5").toParams(),
        )
        assertNull(ProjectOptionsDraft(months = "deux ans").toParams())
        assertNull(ProjectOptionsDraft(downPayment = "-5").toParams())
    }

    @Test
    fun `a resale source is neither edited nor sent back, and nor is a kind the app does not know (MAG-557)`() {
        val sources = listOf(
            ProjectFundingSource(kind = "savings", amountCents = 100_000, accountId = "a1"),
            ProjectFundingSource(kind = "resale", amountCents = 400_000, sourceProjectId = "p7"),
            ProjectFundingSource(kind = "somethingNew", amountCents = 5),
            ProjectFundingSource(kind = "loan", amountCents = 600_000, loanId = "l1"),
        )

        val drafts = FundingDraft.draftsOf(sources)

        assertEquals(listOf(FundingKind.SAVINGS, FundingKind.LOAN), drafts.map { it.kind })
        assertFalse(drafts.any { it.accountId == null && it.kind == FundingKind.SAVINGS })
    }

    @Test
    fun `the editable sources share the amount less the resales the project receives`() {
        val project = Project(
            id = "p9",
            name = "Box",
            amountCents = 1_800_000,
            fundingSources = listOf(ProjectFundingSource(kind = "resale", amountCents = 400_000, sourceProjectId = "p7")),
        )

        assertEquals(1_400_000, FundingDraft.editableAmountCents(project))
        assertEquals(1_800_000, FundingDraft.editableAmountCents(project.copy(fundingSources = emptyList())))
        assertEquals(0, FundingDraft.editableAmountCents(project.copy(amountCents = 100_000)))
    }

    @Test
    fun `no account can be chosen for a resale`() {
        assertEquals(emptyList<Account>(), FundingDraft.eligibleAccounts(FundingKind.RESALE, listOf(Account(id = "chk", name = "Courant", type = "checking"))))
    }
}

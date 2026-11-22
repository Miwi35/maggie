package com.maggie.app.ui.screens.finance

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.DebtTimeline
import com.maggie.app.data.model.Loan
import com.maggie.app.data.model.LoanLabels
import com.maggie.app.data.model.LoanSchedule
import com.maggie.app.data.model.MonthlyRelief
import com.maggie.app.data.model.SavingCapacity
import com.maggie.app.data.model.formatCentsRounded
import com.maggie.app.data.repository.LoanRepository
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.screentest.assertTopToBottom
import com.maggie.app.ui.UiTags
import io.mockk.coEvery
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LoanListScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private val cetelem = Loan(
        id = "l1", name = "Conso", lender = "Cetelem", principalRemainingCents = 420000,
        monthlyPaymentCents = 21000, annualRateBasisPoints = 590, type = LoanLabels.CONSUMER,
    )
    private val immo = Loan(
        id = "l2", name = "Prêt immo", principalRemainingCents = 275000,
        monthlyPaymentCents = 9500, annualRateBasisPoints = 180, type = LoanLabels.AMORTIZING_FIXED,
    )

    private fun timeline(
        reliefs: List<MonthlyRelief> = listOf(
            MonthlyRelief("2028-06", 21000, 21000),
            MonthlyRelief("2029-03", 9500, 30500),
        ),
        income: Boolean = true,
        loans: List<LoanSchedule> = listOf(
            LoanSchedule(id = "l1", name = "Conso", freedOn = "2028-06", totalInterestCents = 23600),
            LoanSchedule(id = "l2", name = "Prêt immo", freedOn = "2029-03", totalInterestCents = 10000),
        ),
    ) = DebtTimeline(
        totalMonthlyPaymentCents = 30500,
        totalPrincipalRemainingCents = 695000,
        reliefByMonth = reliefs,
        loans = loans,
        savingCapacity = SavingCapacity(netCapacityCents = 32000, isIncomeKnown = income),
    )

    private fun show(timeline: DebtTimeline, loans: List<Loan> = listOf(cetelem, immo)) {
        val repository = mockk<LoanRepository>()
        coEvery { repository.getLoans() } returns Result.success(loans)
        coEvery { repository.getTimeline(any()) } returns Result.success(timeline)
        compose.setContent { LoanListScreen(LoanViewModel(repository), onOpenLoan = {}, onBack = {}) }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(UiTags.LOAN_ROW).fetchSemanticsNodes().size == loans.size }
    }

    private fun texts() = compose.onAllNodes(hasText("", substring = true)).fetchSemanticsNodes()
        .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text }

    @Test
    fun `the head says the monthly total and what is left`() {
        show(timeline())

        assertTrue(texts().count { it == "Prêts" } >= 1)
        compose.onNodeWithText("${formatCentsRounded(30500)} / mois").assertIsDisplayed()
        compose.onNodeWithText("de mensualités · ${formatCentsRounded(695000)} restants").assertIsDisplayed()
    }

    @Test
    fun `the relief card says the next relief and describes the steps`() {
        show(timeline())

        compose.onNodeWithText("Libération des charges").assertIsDisplayed()
        assertTrue(texts().any { it.startsWith("Prochaine libération : ") && it.contains("par mois en juin 2028") })
        val description = "Mensualités libérées cumulées : ${formatCentsRounded(21000)} en juin 2028, ${formatCentsRounded(30500)} en mars 2029"
        compose.onNode(hasContentDescription(description)).assertIsDisplayed()
    }

    @Test
    fun `no relief, no relief card`() {
        show(timeline(reliefs = emptyList()))

        assertEquals(0, compose.onAllNodes(hasText("Libération des charges")).fetchSemanticsNodes().size)
    }

    @Test
    fun `the list title sits above the first loan`() {
        show(timeline())

        assertTopToBottom(
            "title" to compose.onNodeWithText("PAR PRIORITÉ DE REMBOURSEMENT"),
            "loan" to compose.onNodeWithText("Conso"),
        )
    }

    @Test
    fun `a loan row says the lender, the rate, the payment, what is left, the end and the interest`() {
        show(timeline())

        compose.onNodeWithText("Cetelem · 5,90 % · ${formatCentsRounded(21000)} / mois").assertIsDisplayed()
        compose.onNodeWithText(formatCentsRounded(420000)).assertIsDisplayed()
        compose.onNodeWithText("Fini en juin 2028 · ${formatCentsRounded(23600)} d’intérêts restants").assertIsDisplayed()
    }

    @Test
    fun `without a lender the type takes its place`() {
        show(timeline())

        compose.onNodeWithText("Amortissable taux fixe · 1,80 % · ${formatCentsRounded(9500)} / mois").assertIsDisplayed()
    }

    @Test
    fun `beyond the horizon the row says so`() {
        show(
            timeline(
                loans = listOf(
                    LoanSchedule(id = "l1", name = "Conso", endsBeyondHorizon = true, totalInterestCents = 23600),
                    LoanSchedule(id = "l2", name = "Prêt immo", freedOn = "2029-03", totalInterestCents = 10000),
                ),
            ),
        )

        compose.onNodeWithText("Se termine au-delà de l'horizon · ${formatCentsRounded(23600)} d’intérêts restants").assertIsDisplayed()
    }

    @Test
    fun `no progress bar, no Libéré en`() {
        show(timeline())

        assertTrue(texts().none { it.contains("Libéré en") || it.contains("Intérêts restants :") })
    }

    @Test
    fun `the saving capacity is the last band, and absent when the income is unknown`() {
        show(timeline())
        compose.onNodeWithText("Capacité d’épargne nette").assertExists()
        compose.onNodeWithText("${formatCentsRounded(32000)} / mois").assertExists()
    }

    @Test
    fun `no capacity band without a known income`() {
        show(timeline(income = false))

        assertEquals(0, compose.onAllNodes(hasText("Capacité", substring = true)).fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodes(hasText("Renseignez", substring = true)).fetchSemanticsNodes().size)
    }
}

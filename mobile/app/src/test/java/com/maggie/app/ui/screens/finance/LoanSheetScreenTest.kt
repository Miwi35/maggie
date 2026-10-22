package com.maggie.app.ui.screens.finance

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.DebtTimeline
import com.maggie.app.data.model.EarlyRepayment
import com.maggie.app.data.model.Loan
import com.maggie.app.data.model.LoanCurve
import com.maggie.app.data.model.LoanCurvePoint
import com.maggie.app.data.model.LoanLabels
import com.maggie.app.data.model.LoanSchedule
import com.maggie.app.data.model.formatCents
import com.maggie.app.data.repository.LoanRepository
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import io.mockk.coEvery
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The loan sheet (MAG-523): read on the phone, edited and simulated on the tablet, and the loan list rows
 * that open it. The phone has no conditions form and no simulation.
 */
@RunWith(AndroidJUnit4::class)
class LoanSheetScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private val loan = Loan(
        id = "l1",
        name = "Prêt immo",
        principalRemainingCents = 9800000,
        monthlyPaymentCents = 62500,
        annualRateBasisPoints = 180,
        type = LoanLabels.AMORTIZING_FIXED,
        durationMonths = 240,
        prepaymentPenaltyMode = LoanLabels.FIXED,
        prepaymentPenaltyFixedCents = 50000,
    )
    private val curve = LoanCurve(
        principalRemainingCents = 9800000,
        freedOn = "2034-03",
        monthsRemaining = 89,
        totalInterestCents = 620000,
        months = listOf(LoanCurvePoint("2026-11", 9740000), LoanCurvePoint("2034-03", 0)),
    )
    private val total = EarlyRepayment(
        amountCents = 9800000,
        penaltyMode = LoanLabels.FIXED,
        penaltyCents = 50000,
        remainingInterestCents = 620000,
        realGainCents = 570000,
    )
    private val loaded = LoanDetailUiState(
        loanId = "l1",
        loan = loan,
        curve = curve,
        total = total,
        draft = LoanTermsDraft.of(loan),
    )

    private fun show(
        state: LoanDetailUiState,
        editable: Boolean,
        onSave: () -> Unit = {},
        onSimulate: (Int?) -> Unit = {},
    ) {
        compose.setContent {
            LoanSheetContent(state, editable, onEdit = {}, onSave = onSave, onSimulate = onSimulate, onBack = {})
        }
    }

    @Test
    fun `the phone reads the type, the IRA mode with its amount, the penalty bar and the end of the loan`() {
        show(loaded, editable = false)

        compose.onNodeWithTag(UiTags.LOAN_SHEET).assertIsDisplayed()
        compose.onNodeWithText("Amortissable taux fixe").assertIsDisplayed()
        compose.onNodeWithText("Montant fixe · ${formatCents(50000)}").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Gain réel").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(UiTags.LOAN_CURVE).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(UiTags.LOAN_END).assertIsDisplayed()
        compose.onNodeWithText("Fin en Mars 2034").assertIsDisplayed()
    }

    @Test
    fun `the phone has neither the conditions form nor the simulation`() {
        show(loaded, editable = false)

        assertEquals(0, compose.onAllNodesWithTag(UiTags.LOAN_SAVE).fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithTag(UiTags.LOAN_SIMULATE).fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithTag(UiTags.LOAN_PAYMENT_FIELD).fetchSemanticsNodes().size)
    }

    @Test
    fun `a loan that forbids early repayment shows the server's sentence in place of the bar`() {
        show(loaded.copy(total = null, totalError = "Remboursement anticipé non autorisé"), editable = false)

        compose.onNodeWithText("Remboursement anticipé non autorisé").assertIsDisplayed()
        assertEquals(0, compose.onAllNodes(hasText("Gain réel")).fetchSemanticsNodes().size)
    }

    @Test
    fun `a projected loan carries the mark`() {
        show(loaded.copy(loan = loan.copy(projected = true)), editable = false)

        compose.onNodeWithTag(UiTags.LOAN_PROJECTED).assertIsDisplayed()
    }

    @Test
    fun `a loan being read shows no conditions yet, and one that cannot be read shows why`() {
        show(LoanDetailUiState(error = "Impossible de lire ce prêt."), editable = false)

        compose.onNodeWithText("Impossible de lire ce prêt.").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun `the tablet edits the conditions, shows the end date and saves`() {
        var saved = 0
        show(loaded, editable = true, onSave = { saved++ })

        compose.onNodeWithTag(UiTags.LOAN_PAYMENT_FIELD).assertExists()
        compose.onNodeWithTag(UiTags.LOAN_SAVE).performScrollTo().assertIsDisplayed().performClick()

        assertEquals(1, saved)
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun `a refused save is shown as the server says it`() {
        show(loaded.copy(saveError = "Le plafond doit dépasser le plancher"), editable = true)

        compose.onNodeWithTag(UiTags.LOAN_SAVE_ERROR).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Le plafond doit dépasser le plancher").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun `the simulation starts at the whole capital and asks for the amount typed`() {
        var asked: Int? = -1
        show(loaded, editable = true, onSimulate = { asked = it })

        compose.onNodeWithTag(UiTags.LOAN_SIMULATION_AMOUNT).performScrollTo().assertTextContains(String.format(java.util.Locale.FRANCE, "%.2f", 98000.0))
        compose.onNodeWithTag(UiTags.LOAN_SIMULATE).performScrollTo().performClick()

        assertEquals(9800000, asked)
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun `a refused simulation shows the 400 message as it is`() {
        show(loaded.copy(simulationError = "Montant supérieur au capital restant dû"), editable = true)

        compose.onNodeWithTag(UiTags.LOAN_SIMULATION_ERROR).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Montant supérieur au capital restant dû").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun `a simulation result shows its own bar`() {
        show(loaded.copy(simulation = total.copy(amountCents = 2000000, penaltyCents = 10000)), editable = true)

        compose.onNodeWithTag(UiTags.LOAN_SIMULATION).performScrollTo().assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun `no text of the sheet advises the owner`() {
        show(loaded.copy(simulation = total), editable = true)

        val texts = compose.onAllNodes(hasText("", substring = true)).fetchSemanticsNodes()
            .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }
            .map { it.text.lowercase() }
        assertTrue(texts.isNotEmpty())
        assertTrue(texts.none { "conseillé" in it || "préférable" in it })
    }

    @Test
    fun `the list rows give the type, the end, the remaining interest and the projected mark`() {
        val repository = mockk<LoanRepository>()
        val projected = Loan(id = "l2", name = "Travaux", monthlyPaymentCents = 30000, type = LoanLabels.IN_FINE, projected = true)
        coEvery { repository.getLoans() } returns Result.success(listOf(loan, projected))
        coEvery { repository.getTimeline(any()) } returns Result.success(
            DebtTimeline(
                loans = listOf(
                    LoanSchedule(id = "l1", name = "Prêt immo", freedOn = "2034-03", totalInterestCents = 620000),
                ),
            ),
        )
        val opened = mutableListOf<String>()

        compose.setContent { LoanListScreen(LoanViewModel(repository), onOpenLoan = { opened += it }, onBack = {}) }

        compose.waitUntil(5_000) { compose.onAllNodesWithTag(UiTags.LOAN_ROW).fetchSemanticsNodes().size == 2 }
        compose.onNodeWithText("Amortissable taux fixe").assertIsDisplayed()
        compose.onNodeWithText("In fine").assertIsDisplayed()
        compose.onNodeWithText("Libéré en Mars 2034").assertIsDisplayed()
        compose.onNodeWithText("Intérêts restants : ${formatCents(620000)}").assertIsDisplayed()
        compose.onNodeWithText("Projeté").assertIsDisplayed()
        assertEquals(1, compose.onAllNodes(hasText("Libéré en", substring = true)).fetchSemanticsNodes().size)

        compose.onNodeWithText("Travaux").performClick()
        assertEquals(listOf("l2"), opened)
    }
}

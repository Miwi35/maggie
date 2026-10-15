package com.maggie.app.ui.screens.finance

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.api.TransactionCreateRequest
import com.maggie.app.data.model.AdvanceRepaymentProposal
import com.maggie.app.data.model.SalaryDelay
import com.maggie.app.data.model.SavingsAdvance
import com.maggie.app.data.model.SavingsAdvances
import com.maggie.app.data.model.SavingsSource
import com.maggie.app.data.model.UnproposedAdvance
import com.maggie.app.data.model.formatCents
import com.maggie.app.screentest.FakeSavingsAdvances
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The savings advances screen (MAG-278): what is still to give back, the counter-transfers
 * the server proposes, and the salary day that tells whether the salary is late.
 */
@RunWith(AndroidJUnit4::class)
class SavingsAdvancesScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private val proposal = AdvanceRepaymentProposal(
        fromAccountId = "acc-checking",
        toAccountId = "acc-livret",
        amountCents = 50000,
        advanceId = "adv-1",
        label = "Remise avance Livret A",
        isPartial = true,
    )

    private val withAdvances = SavingsAdvances(
        advances = listOf(
            SavingsAdvance("adv-1", "acc-livret", "Livret A", 150000, "2026-10-11", 100000, 50000, false),
            SavingsAdvance("adv-0", "acc-livret", "Livret A", 20000, "2026-08-02", 20000, 0, true),
        ),
        bySource = listOf(SavingsSource("acc-livret", "Livret A", 50000, 1)),
        totalOutstandingCents = 50000,
        salaryDelay = SalaryDelay(isConfigured = true, salaryExpectedDay = 5, referenceCents = 250000, isLate = true, delayDays = 6),
        proposals = listOf(proposal),
        proposalsTotalCents = 50000,
        unproposed = listOf(UnproposedAdvance("adv-2", "acc-pel", "PEL", 30000, "insufficient_balance")),
        unproposedCents = 30000,
    )

    private fun show(fake: FakeSavingsAdvances) {
        compose.setContent { SavingsAdvancesScreen(viewModel = fake.viewModel, onBack = {}) }
        compose.waitForIdle()
    }

    private fun scrollTo(text: String) {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(text, substring = true))
    }

    @Test
    fun `open and settled advances are drawn with the exact words`() {
        show(FakeSavingsAdvances(withAdvances))

        compose.onNodeWithText("Avances d'épargne").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.SAVINGS_ADVANCES_TOTAL).assertIsDisplayed()
        scrollTo("Avances ouvertes")
        compose.onNodeWithText("Avances ouvertes").assertIsDisplayed()
        scrollTo("Avancé")
        compose.onAllNodesWithText("Avancé").onFirst().assertIsDisplayed()
        compose.onAllNodesWithText("Rendu").onFirst().assertIsDisplayed()
        compose.onAllNodesWithText("Reste à rendre").onFirst().assertIsDisplayed()
        scrollTo("Avances soldées")
        compose.onNodeWithText("Avances soldées").assertIsDisplayed()
    }

    @Test
    fun `a proposal shows the exact amount, its destination and the partial mark`() {
        show(FakeSavingsAdvances(withAdvances))

        scrollTo("Remettre")
        compose.onNodeWithText("Remettre ${formatCents(50000)} sur Livret A").assertIsDisplayed()
        scrollTo("Montant réduit")
        compose.onNodeWithText("Montant réduit au solde disponible").assertIsDisplayed()
        scrollTo("PEL")
        compose.onNodeWithText("PEL : ${formatCents(30000)} — solde insuffisant sur le compte courant").assertIsDisplayed()
    }

    @Test
    fun `the help text says the button books a transaction and moves no money`() {
        show(FakeSavingsAdvances(withAdvances))

        scrollTo("aucun virement bancaire")
        compose.onNodeWithText("aucun virement bancaire", substring = true).assertIsDisplayed()
    }

    @Test
    fun `je l'ai fait books the debit then the credit`() {
        val fake = FakeSavingsAdvances(withAdvances)
        show(fake)

        scrollTo("Remettre")
        compose.onNodeWithTag(UiTags.savingsAdvanceDone("adv-1")).performClick()
        compose.waitForIdle()

        assertEquals(
            listOf(
                TransactionCreateRequest("/api/accounts/acc-checking", -50000, "Remise avance Livret A", null, "EUR", "spent", "2026-10-13"),
                TransactionCreateRequest("/api/accounts/acc-livret", 50000, "Remise avance Livret A", null, "EUR", "spent", "2026-10-13"),
            ),
            fake.legs,
        )
    }

    @Test
    fun `no advance says so instead of drawing a broken screen`() {
        show(FakeSavingsAdvances(SavingsAdvances()))

        compose.onNodeWithText("Aucune avance en cours").assertIsDisplayed()
        compose.onNodeWithText("Reste à rendre").assertIsDisplayed()
    }

    @Test
    fun `an unset salary day is asked and no delay is shown`() {
        show(FakeSavingsAdvances(withAdvances.copy(salaryDelay = SalaryDelay())))

        compose.onNodeWithText("Quel jour attendez-vous votre salaire ?").assertIsDisplayed()
        compose.onNodeWithText("Salaire en retard", substring = true).assertDoesNotExist()
    }

    @Test
    fun `a late salary shows the banner`() {
        show(FakeSavingsAdvances(withAdvances))

        compose.onNodeWithText("Salaire en retard de 6 jours").assertIsDisplayed()
    }

    @Test
    fun `a day without a reference salary says the delay cannot be computed`() {
        val delay = SalaryDelay(isConfigured = false, salaryExpectedDay = 5)
        show(FakeSavingsAdvances(withAdvances.copy(salaryDelay = delay)))

        compose.onNodeWithText("Pas de salaire de référence : le retard ne peut pas être calculé").assertIsDisplayed()
        compose.onNodeWithText("Salaire en retard", substring = true).assertDoesNotExist()
    }

    @Test
    fun `the salary day typed is the one saved`() {
        val fake = FakeSavingsAdvances(withAdvances.copy(salaryDelay = SalaryDelay()))
        show(fake)

        compose.onNodeWithTag(UiTags.SAVINGS_ADVANCES_SALARY_DAY).performTextInput("28")
        compose.onNodeWithTag(UiTags.SAVINGS_ADVANCES_SAVE_DAY).performClick()
        compose.waitForIdle()

        assertEquals(listOf<Int?>(28), fake.savedDays)
    }

    @Test
    fun `an emptied field forgets the day`() {
        val fake = FakeSavingsAdvances(withAdvances)
        show(fake)

        compose.onNodeWithTag(UiTags.SAVINGS_ADVANCES_SALARY_DAY).performTextClearance()
        compose.onNodeWithTag(UiTags.SAVINGS_ADVANCES_SAVE_DAY).performClick()
        compose.waitForIdle()

        assertEquals(listOf<Int?>(null), fake.savedDays)
    }

    @Test
    fun `a failed load offers a retry`() {
        show(FakeSavingsAdvances(loadFailure = RuntimeException("boom")))

        compose.onNodeWithText("Chargement impossible. Vérifiez votre connexion.").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.SAVINGS_ADVANCES_RETRY).assertIsDisplayed()
        compose.onNodeWithText("Réessayer").assertIsDisplayed()
    }
}

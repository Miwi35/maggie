package com.maggie.app.ui.screens.finance

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.Transaction
import com.maggie.app.screentest.FakeTransactionTransfers
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.screentest.Seed
import com.maggie.app.ui.UiTags
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The « Virement interne » badge and the toggle behind it (MAG-272), and the « Rejet »
 * badge with its release (MAG-350).
 *
 * `TransactionViewModelTest` says when the state changes; this says it reaches a drawn
 * badge, and that marking goes through a screen of its own, not a dropdown.
 */
@RunWith(AndroidJUnit4::class)
class TransactionTransferScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private fun show(server: FakeTransactionTransfers) = compose.setContent {
        TransactionListScreen(viewModel = server.viewModel, accountName = "Livret", onBack = {})
    }

    @Test
    fun `only the paired line carries the badge`() {
        show(FakeTransactionTransfers())

        compose.onNodeWithText("Virement vers Courant").assertIsDisplayed()
        compose.onNodeWithText("Supermarché").assertIsDisplayed()
        compose.onAllNodesWithTag(UiTags.TRANSFER_BADGE).assertCountEquals(1)
    }

    @Test
    fun `the detail names the counterpart, and releasing it takes the badge away`() {
        val server = FakeTransactionTransfers()
        show(server)

        compose.onNodeWithText("Virement vers Courant").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Détecté automatiquement").assertIsDisplayed()
        compose.onNodeWithText("Contrepartie : Courant · 2026-09-13 · Virement du Livret").assertIsDisplayed()

        compose.onNodeWithTag(UiTags.TRANSFER_TOGGLE).performClick()
        compose.waitForIdle()

        assertEquals(listOf("tx-out"), server.released)
        compose.onNodeWithText("C'est un virement interne").assertIsDisplayed()
        compose.onAllNodesWithTag(UiTags.TRANSFER_BADGE).assertCountEquals(0)
    }

    @Test
    fun `marking an ordinary line goes through the full-screen counterpart search`() {
        val server = FakeTransactionTransfers(
            transactions = listOf(Transaction(id = "tx-out", label = "Virement vers Courant", amountCents = -300000, bookedAt = "2026-09-12")),
        )
        show(server)

        compose.onNodeWithText("Virement vers Courant").performClick()
        compose.waitForIdle()
        compose.onAllNodesWithTag(UiTags.TRANSFER_BADGE).assertCountEquals(0)

        compose.onNodeWithTag(UiTags.TRANSFER_TOGGLE).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(UiTags.TRANSFER_SEARCH).assertIsDisplayed()
        compose.onNodeWithText("Virement du Livret").performClick()
        compose.waitForIdle()

        assertEquals(listOf<String?>("tx-in"), server.marked)
        compose.onNodeWithText("Marqué à la main").assertIsDisplayed()
        compose.onNodeWithText("Contrepartie : Courant · 2026-09-13 · Virement du Livret").assertIsDisplayed()
        compose.onAllNodesWithTag(UiTags.TRANSFER_BADGE).assertCountEquals(1)
    }

    private fun rejections() = FakeTransactionTransfers(
        transactions = listOf(Seed.rejectedDebit, Seed.rejectedCredit, Seed.groceries),
    )

    @Test
    fun `a rejected payment reads Rejeté, the credit that gave it back Rejet`() {
        show(rejections())

        compose.onNodeWithText("Rejeté").assertIsDisplayed()
        compose.onNodeWithText("Rejet").assertIsDisplayed()
        compose.onAllNodesWithTag(UiTags.REJECTION_BADGE).assertCountEquals(2)
        compose.onAllNodesWithTag(UiTags.TRANSFER_BADGE).assertCountEquals(0)
    }

    @Test
    fun `the detail of the credit names the rejected payment, and releasing frees both lines`() {
        val server = rejections()
        show(server)

        compose.onNodeWithText("REJET PRLV SEPA").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Rejet de PRELEVEMENT EDF").assertIsDisplayed()
        compose.onAllNodesWithTag(UiTags.TRANSFER_TOGGLE).assertCountEquals(0)

        compose.onNodeWithTag(UiTags.REJECTION_RELEASE).performClick()
        compose.waitForIdle()

        assertEquals(listOf("tx-rej"), server.released)
        compose.onAllNodesWithTag(UiTags.REJECTION_BADGE).assertCountEquals(0)
        compose.onNodeWithText("C'est un virement interne").assertIsDisplayed()

        compose.onNodeWithContentDescription("Retour").performClick()
        compose.waitForIdle()
        compose.onAllNodesWithTag(UiTags.REJECTION_BADGE).assertCountEquals(0)
    }
}

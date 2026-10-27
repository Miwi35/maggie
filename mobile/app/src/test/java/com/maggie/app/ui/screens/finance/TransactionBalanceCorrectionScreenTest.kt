package com.maggie.app.ui.screens.finance

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.screentest.FakeTransactionTransfers
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** « Corriger le solde » of a hand-kept account, from the phone's account screen (MAG-632). */
@RunWith(AndroidJUnit4::class)
class TransactionBalanceCorrectionScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private fun show(correction: BalanceCorrection?) {
        val server = FakeTransactionTransfers(transactions = emptyList())
        compose.setContent {
            TransactionListScreen(
                viewModel = server.viewModel,
                accountName = "Espèces",
                onBack = {},
                balanceCorrection = correction,
            )
        }
    }

    @Test
    fun `a manual account offers Corriger le solde in the menu of its bar`() {
        show(BalanceCorrection(currentCents = 125000, onCorrect = {}))

        compose.onNodeWithTag(UiTags.ACCOUNT_MENU).performClick()

        compose.onNodeWithTag(UiTags.ACCOUNT_CORRECT_BALANCE).assertIsDisplayed()
    }

    @Test
    fun `an account fed by a bank has no menu`() {
        show(null)

        compose.onNodeWithTag(UiTags.ACCOUNT_MENU).assertDoesNotExist()
        compose.onNodeWithTag(UiTags.ACCOUNT_CORRECT_BALANCE).assertDoesNotExist()
    }

    // A text field in a dialog never reads idle on Robolectric's small default window: the same size as BudgetAlertThresholdScreenTest.
    @Test
    @Config(qualifiers = "w800dp-h1280dp-xhdpi")
    fun `the form starts from the balance and confirming hands it to the correction`() {
        var corrected: Int? = null
        show(BalanceCorrection(currentCents = 125000, onCorrect = { corrected = it }))

        compose.onNodeWithTag(UiTags.ACCOUNT_MENU).performClick()
        compose.onNodeWithTag(UiTags.ACCOUNT_CORRECT_BALANCE).performClick()
        compose.onNodeWithTag(UiTags.ACCOUNT_BALANCE_INPUT).assertTextContains("1250,00")
        compose.onNodeWithTag(UiTags.ACCOUNT_BALANCE_INPUT).performTextReplacement("99,90")
        compose.onNodeWithTag(UiTags.ACCOUNT_BALANCE_SAVE).performClick()

        assertEquals(9990, corrected)
        compose.onNodeWithTag(UiTags.ACCOUNT_BALANCE_INPUT).assertDoesNotExist()
    }
}

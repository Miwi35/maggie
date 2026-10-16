package com.maggie.app.ui.screens.finance

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.Account
import com.maggie.app.data.model.CushionAccount
import com.maggie.app.data.model.CushionStatus
import com.maggie.app.screentest.ScreenRule
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Wherever the phone lists an account, it reads « Banque · devise · nom » (MAG-501). */
@RunWith(AndroidJUnit4::class)
class AccountLabelScreenTest {

    @get:Rule
    val compose = ScreenRule()

    @Test
    fun `the account list names each account with its bank and currency`() {
        val viewModel = mockk<AccountViewModel>(relaxed = true)
        every { viewModel.uiState } returns MutableStateFlow(
            AccountUiState(
                accounts = listOf(
                    Account(id = "a1", name = "Compte courant", bank = "Boursorama"),
                    Account(id = "a2", name = "Compte courant", bank = "Crédit Agricole"),
                    Account(id = "a3", name = "Livret", bank = null),
                ),
            ),
        )
        compose.setContent { AccountListScreen(viewModel = viewModel, onBack = {}) }

        compose.onNodeWithText("Boursorama · EUR · Compte courant").assertIsDisplayed()
        compose.onNodeWithText("Crédit Agricole · EUR · Compte courant").assertIsDisplayed()
        compose.onNodeWithText("EUR · Livret").assertIsDisplayed()
    }

    @Test
    fun `the cushion lists its accounts with their bank and currency`() {
        val status = CushionStatus(
            accounts = listOf(
                CushionAccount(id = "a1", name = "Livret A", bank = "Boursorama", balanceCents = 500000),
                CushionAccount(id = "a2", name = "Livret", bank = null, balanceCents = 1000),
            ),
        )
        compose.setContent { CushionSummaryCard(status = status) }

        compose.onNodeWithText("Boursorama · EUR · Livret A — 5000.00 EUR").assertIsDisplayed()
        compose.onNodeWithText("EUR · Livret — 10.00 EUR").assertIsDisplayed()
    }
}

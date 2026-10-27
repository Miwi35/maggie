package com.maggie.app.ui.screens.finance

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.Account
import com.maggie.app.screentest.ScreenRule
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Deleting an account asks first, as the admin does: its transactions go with it (MAG-632). */
@RunWith(AndroidJUnit4::class)
class AccountDeleteScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private fun show(): AccountViewModel {
        val viewModel = mockk<AccountViewModel>(relaxed = true)
        every { viewModel.uiState } returns MutableStateFlow(
            AccountUiState(accounts = listOf(Account(id = "a1", name = "Livret", bank = "Boursorama"))),
        )
        compose.setContent { AccountListScreen(viewModel = viewModel, onBack = {}) }
        return viewModel
    }

    @Test
    fun `the bin asks before deleting, and nothing is deleted yet`() {
        val viewModel = show()

        compose.onNodeWithContentDescription("Supprimer").performClick()

        compose.onNodeWithText("Supprimer le compte « Livret » ?").assertIsDisplayed()
        verify(exactly = 0) { viewModel.deleteAccount(any()) }
    }

    @Test
    fun `Annuler keeps the account`() {
        val viewModel = show()

        compose.onNodeWithContentDescription("Supprimer").performClick()
        compose.onNodeWithText("Annuler").performClick()

        compose.onNodeWithText("Supprimer le compte « Livret » ?").assertDoesNotExist()
        verify(exactly = 0) { viewModel.deleteAccount(any()) }
    }

    @Test
    fun `Confirmer deletes the account`() {
        val viewModel = show()

        compose.onNodeWithContentDescription("Supprimer").performClick()
        compose.onNodeWithText("Confirmer").performClick()

        verify(exactly = 1) { viewModel.deleteAccount("a1") }
        compose.onNodeWithText("Supprimer le compte « Livret » ?").assertDoesNotExist()
    }
}

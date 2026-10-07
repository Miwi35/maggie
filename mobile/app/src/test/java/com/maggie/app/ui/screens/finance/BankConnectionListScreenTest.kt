package com.maggie.app.ui.screens.finance

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.BankConnection
import com.maggie.app.screentest.FakeBankConnections
import com.maggie.app.screentest.ScreenRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * How a link reads on the phone (MAG-45, retour de recette : « les banques
 * apparaissent non connectées »).
 *
 * The status was a disabled chip: « Connectée » drawn at the faded alpha of
 * something switched off, next to a « Reconnecter » offered on every card. A
 * working bank read as a broken one. And the label came from the stored status
 * alone, which stays `active` after the consent runs out — so the one bank that
 * did need the user said « Connectée » too.
 */
@RunWith(AndroidJUnit4::class)
class BankConnectionListScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private fun link(
        needsReconnecting: Boolean = false,
        daysBeforeExpiry: Int? = 60,
    ) = BankConnection(
        id = "conn-1",
        bankName = "Mock Bank",
        status = "active",
        needsReconnecting = needsReconnecting,
        daysBeforeExpiry = daysBeforeExpiry,
    )

    @Test
    fun `a working link reads as connected and asks for nothing`() {
        val server = FakeBankConnections(listOf(link()))
        compose.setContent { BankConnectionListScreen(viewModel = server.viewModel, onBack = {}) }

        compose.onNodeWithText("Connectée").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Reconnecter").assertDoesNotExist()
    }

    @Test
    fun `a link whose consent ran out says so and offers the way back`() {
        val server = FakeBankConnections(listOf(link(needsReconnecting = true, daysBeforeExpiry = -2)))
        compose.setContent { BankConnectionListScreen(viewModel = server.viewModel, onBack = {}) }

        compose.onNodeWithText("À reconnecter").assertIsDisplayed()
        compose.onNodeWithText("Connectée").assertDoesNotExist()
        compose.onNodeWithText("Reconnecter").assertIsDisplayed().assertIsEnabled()
    }

    @Test
    fun `a consent about to run out can be renewed ahead`() {
        val server = FakeBankConnections(listOf(link(daysBeforeExpiry = 3)))
        compose.setContent { BankConnectionListScreen(viewModel = server.viewModel, onBack = {}) }

        compose.onNodeWithText("Connectée").assertIsDisplayed()
        compose.onNodeWithText("Reconnecter").assertIsDisplayed()
    }
}

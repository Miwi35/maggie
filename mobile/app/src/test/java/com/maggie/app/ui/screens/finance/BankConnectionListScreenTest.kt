package com.maggie.app.ui.screens.finance

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.BankConnection
import com.maggie.app.screentest.FakeBankConnections
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.screentest.assertTopToBottom
import com.maggie.app.ui.UiTags
import org.junit.Assert.assertEquals
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
        status: String = "active",
    ) = BankConnection(
        id = "conn-1",
        bankName = "Mock Bank",
        status = status,
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
        compose.onNodeWithText("Accès expiré", substring = true).assertDoesNotExist()
    }

    @Test
    fun `a pending link reads En attente and offers Reprendre, without a sentence`() {
        val server = FakeBankConnections(listOf(link(status = "pending", daysBeforeExpiry = null)))
        compose.setContent { BankConnectionListScreen(viewModel = server.viewModel, onBack = {}) }

        compose.onNodeWithText("En attente").assertIsDisplayed()
        compose.onNodeWithText("Jamais synchronisée").assertIsDisplayed()
        compose.onNodeWithText("Reprendre").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("L'autorisation n'a pas été", substring = true).assertDoesNotExist()
    }

    @Test
    fun `the connect card is at least 64 dp high and ends the list`() {
        val server = FakeBankConnections(listOf(link()))
        compose.setContent { BankConnectionListScreen(viewModel = server.viewModel, onBack = {}) }

        compose.onNodeWithTag(UiTags.BANK_CONNECT).assertHeightIsAtLeast(64.dp)
        assertTopToBottom(
            "Mock Bank" to compose.onNodeWithText("Mock Bank"),
            "Connecter une banque" to compose.onNodeWithTag(UiTags.BANK_CONNECT),
        )
    }

    @Test
    fun `a bank card reads its name, its state, its last sync, then its action, top to bottom`() {
        val server = FakeBankConnections(listOf(link(status = "pending", daysBeforeExpiry = null)))
        compose.setContent { BankConnectionListScreen(viewModel = server.viewModel, onBack = {}) }

        assertTopToBottom(
            "Mock Bank" to compose.onNodeWithText("Mock Bank"),
            "Jamais synchronisée" to compose.onNodeWithText("Jamais synchronisée"),
            "Reprendre" to compose.onNodeWithText("Reprendre"),
        )
    }

    @Test
    fun `a consent about to run out can be renewed ahead`() {
        val server = FakeBankConnections(listOf(link(daysBeforeExpiry = 3)))
        compose.setContent { BankConnectionListScreen(viewModel = server.viewModel, onBack = {}) }

        compose.onNodeWithText("Connectée").assertIsDisplayed()
        compose.onNodeWithText("Reconnecter").assertIsDisplayed()
        compose.onNodeWithText("L'accès expire dans 3 jour(s).").assertIsDisplayed()
    }

    @Test
    fun `the connect entry is there when no bank is linked yet`() {
        val server = FakeBankConnections(emptyList())
        compose.setContent { BankConnectionListScreen(viewModel = server.viewModel, onBack = {}) }

        compose.onNodeWithText("Connecter une banque").assertIsDisplayed().assertIsEnabled()
    }

    @Test
    fun `the connect entry ends the list of linked banks and opens the search`() {
        val server = FakeBankConnections(listOf(link()))
        var opened = 0
        compose.setContent { BankConnectionListScreen(viewModel = server.viewModel, onBack = {}, onConnect = { opened++ }) }

        compose.onNodeWithText("Mock Bank").assertIsDisplayed()
        compose.onNodeWithText("Connecter une banque").assertIsDisplayed().performClick()
        assertEquals(1, opened)
    }
}

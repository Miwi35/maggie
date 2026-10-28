package com.maggie.app.ui.navigation

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.Account
import com.maggie.app.screentest.FakeTransactionTransfers
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.screens.finance.AccountUiState
import com.maggie.app.ui.screens.finance.AccountViewModel
import com.maggie.app.ui.screens.finance.FinanceSummaryViewModel
import com.maggie.app.ui.screens.finance.TransactionViewModel
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.loadKoinModules
import org.koin.core.context.stopKoin
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * « Finance › Comptes ▾ » on a phone (MAG-632): the section's page, like every other section's — the breadcrumb, a
 * card per account — and not a screen of its own with a back arrow and a carousel. An account opens its transactions,
 * a detail screen with the arrow.
 */
@RunWith(AndroidJUnit4::class)
class AccountsSectionNavGraphScreenTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private lateinit var scenario: ActivityScenario<NavGraphHostActivity>

    @After
    fun tearDown() = stopKoin()

    private fun launchOnAccounts() {
        startNavGraphApp()
        val accounts = mockk<AccountViewModel>(relaxed = true) {
            every { uiState } returns MutableStateFlow(
                AccountUiState(
                    accounts = listOf(
                        Account(id = "a1", name = "Compte courant", bank = "Boursorama"),
                        Account(id = "a2", name = "Livret"),
                    ),
                    selectedAccountId = "a1",
                ),
            )
        }
        val summary = mockk<FinanceSummaryViewModel>(relaxed = true) {
            every { blocks } returns MutableStateFlow(emptyMap())
        }
        val transactions = FakeTransactionTransfers().viewModel
        loadKoinModules(
            module {
                viewModel { accounts }
                viewModel { summary }
                viewModel<TransactionViewModel> { transactions }
            },
        )
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, NavGraphHostActivity::class.java))
        val link = Intent(Intent.ACTION_VIEW, Uri.parse("${DeepLinks.SCHEME}://finance/accounts"))
            .setClass(app, NavGraphHostActivity::class.java)
        scenario = ActivityScenario.launch(link)
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
    }

    @Test
    @Config(qualifiers = PHONE_PORTRAIT)
    fun `Comptes on a phone is a section page under the breadcrumb, with no back arrow and no carousel`() {
        launchOnAccounts()

        compose.onNodeWithTag(UiTags.BREADCRUMB_SECTION).assertIsDisplayed()
        compose.onNodeWithText("EUR · Compte courant", substring = true).assertIsDisplayed()
        compose.onNodeWithText("EUR · Livret", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Retour").assertDoesNotExist()
        compose.onNodeWithTag("account_pager").assertDoesNotExist()
        compose.onNodeWithTag("account_dots").assertDoesNotExist()
        scenario.close()
    }

    @Test
    @Config(qualifiers = PHONE_PORTRAIT)
    fun `an account opens its transactions on a detail screen with the back arrow`() {
        launchOnAccounts()

        compose.onNodeWithText("EUR · Livret", substring = true).performClick()
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Retour").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.BREADCRUMB_SECTION).assertDoesNotExist()
        compose.onNodeWithTag(UiTags.TRANSACTIONS_TAB).assertIsDisplayed()
        scenario.close()
    }

    private companion object {
        const val PHONE_PORTRAIT = "w411dp-h891dp-port-xhdpi"
    }
}

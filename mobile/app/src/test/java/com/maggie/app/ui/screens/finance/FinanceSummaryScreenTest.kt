package com.maggie.app.ui.screens.finance

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.CushionStatus
import com.maggie.app.data.model.SavingsAdvance
import com.maggie.app.data.model.SavingsAdvances
import com.maggie.app.data.model.TreasuryOutlook
import com.maggie.app.data.repository.CushionRepository
import com.maggie.app.data.repository.SavingsAdvancesRepository
import com.maggie.app.data.repository.TreasuryRepository
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.navigation.FinanceMenu
import com.maggie.app.ui.navigation.Screen
import io.mockk.coEvery
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The summary pages (MAG-426): a block per aspect, each a way into its screen — and no page
 * for a menu whose home is a screen itself.
 */
@RunWith(AndroidJUnit4::class)
class FinanceSummaryScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private fun viewModel(cushionFails: Boolean = false): FinanceSummaryViewModel {
        val cushion = mockk<CushionRepository>()
        coEvery { cushion.getStatus() } returns if (cushionFails) {
            Result.failure(IllegalStateException("Service indisponible"))
        } else {
            Result.success(CushionStatus(isConfigured = true, targetCents = 900000, currentCents = 450000))
        }
        val advances = mockk<SavingsAdvancesRepository>()
        coEvery { advances.getSavingsAdvances() } returns Result.success(
            SavingsAdvances(
                advances = listOf(SavingsAdvance(id = "a1", amountCents = 20000, outstandingCents = 20000)),
                totalOutstandingCents = 20000,
            ),
        )
        val treasury = mockk<TreasuryRepository>()
        coEvery { treasury.getOutlook() } returns Result.success(TreasuryOutlook())
        return FinanceSummaryViewModel(
            cushion, advances, treasury, mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(),
        )
    }

    @Test
    fun `the treasury page has a block for the cushion and one for the savings advances`() {
        val opened = mutableListOf<String>()
        compose.setContent {
            FinanceSummaryScreen(FinanceMenu.TREASURY, viewModel(), onBack = {}, onOpen = { opened += it })
        }

        compose.onNodeWithTag(UiTags.financeBlock(Screen.Cushion.route)).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.financeBlock(Screen.SavingsAdvances.route)).assertIsDisplayed()
        compose.onNodeWithText("Matelas").assertIsDisplayed()
        compose.onNodeWithText("Avances d'épargne").assertIsDisplayed()
        compose.onNodeWithText("à remettre", substring = true).assertIsDisplayed()
    }

    @Test
    fun `a tap on a block opens its screen`() {
        val opened = mutableListOf<String>()
        compose.setContent {
            FinanceSummaryScreen(FinanceMenu.TREASURY, viewModel(), onBack = {}, onOpen = { opened += it })
        }

        compose.onNodeWithTag(UiTags.financeBlock(Screen.Cushion.route)).performClick()
        compose.onNodeWithTag(UiTags.financeBlock(Screen.SavingsAdvances.route)).performClick()

        assertEquals(listOf(Screen.Cushion.route, Screen.SavingsAdvances.route), opened)
    }

    @Test
    fun `a block whose figures failed says so and still opens its screen`() {
        val opened = mutableListOf<String>()
        compose.setContent {
            FinanceSummaryScreen(
                FinanceMenu.TREASURY,
                viewModel(cushionFails = true),
                onBack = {},
                onOpen = { opened += it },
            )
        }

        compose.onNodeWithText("Chiffres indisponibles").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.financeBlock(Screen.SavingsAdvances.route)).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.financeBlock(Screen.Cushion.route)).performClick()

        assertEquals(listOf(Screen.Cushion.route), opened)
    }

    @Test
    fun `the back arrow asks to leave the page`() {
        var backs = 0
        compose.setContent {
            FinanceSummaryScreen(FinanceMenu.TREASURY, viewModel(), onBack = { backs++ }, onOpen = {})
        }

        compose.onNodeWithContentDescription("Retour").performClick()

        assertEquals(1, backs)
    }

    @Test
    fun `each page lists the screens that exist and nothing else`() {
        assertEquals(
            listOf(Screen.TransactionsToSort.route, Screen.RecognisedToControl.route, Screen.MonthlyReview.route, Screen.CategorizationRuleList.route, Screen.RecurringOperations.route),
            summaryEntries(FinanceMenu.REVIEW).map { it.route },
        )
        assertEquals(
            listOf(Screen.Treasury.route, Screen.Cushion.route, Screen.SavingsAdvances.route),
            summaryEntries(FinanceMenu.TREASURY).map { it.route },
        )
        assertEquals(
            listOf(Screen.LoanList.route, Screen.FinanceDashboard.route),
            summaryEntries(FinanceMenu.PLANNING).map { it.route },
        )
        assertEquals(
            listOf(Screen.BankConnectionList.route, Screen.CategoryList.route),
            summaryEntries(FinanceMenu.SETTINGS).map { it.route },
        )
    }

    @Test
    fun `a menu whose home is a screen has no summary page`() {
        listOf(FinanceMenu.DASHBOARD, FinanceMenu.ACCOUNTS, FinanceMenu.BUDGET).forEach {
            assertTrue(summaryEntries(it).isEmpty())
        }
    }

    @Test
    fun `every block of a page opens a route of the module`() {
        val module = FinanceMenu.entries.flatMap { it.routes }.toSet()

        FinanceMenu.entries.flatMap(::summaryEntries).forEach {
            assertTrue("${it.route} is not a finance screen", it.route in module)
        }
    }
}

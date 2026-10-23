package com.maggie.app.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.BalanceBeforeSalary
import com.maggie.app.data.model.FinanceDashboard
import com.maggie.app.data.model.TreasuryBannerState
import com.maggie.app.data.model.TreasuryOutlook
import com.maggie.app.data.model.TreasuryOverdraft
import com.maggie.app.data.model.formatCents
import com.maggie.app.screentest.FakeMonthBalance
import com.maggie.app.screentest.FakeUpcoming
import com.maggie.app.screentest.FakeLoadedFinanceDashboard
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.navigation.Screen
import com.maggie.app.ui.screens.finance.FinanceDashboardScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TreasuryBannerScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private val banner = TreasuryBannerState(
        overdraftOn = "2026-10-20",
        balanceBeforeSalaryCents = -30000,
        balanceBeforeSalaryOn = "2026-10-24",
    )

    @Test
    fun `the banner says when the overdraft comes and the balance the eve of the salary`() {
        compose.setContent {
            TreasuryBanner(
                overdraftOn = banner.overdraftOn,
                balanceBeforeSalaryCents = banner.balanceBeforeSalaryCents,
                balanceBeforeSalaryOn = banner.balanceBeforeSalaryOn,
                onClick = {},
            )
        }

        compose.onNodeWithText("Découvert prévu le 20 octobre").assertIsDisplayed()
        compose.onNodeWithText("solde attendu la veille du salaire (24 octobre) : ${formatCents(-30000)}").assertIsDisplayed()
    }

    @Test
    fun `a tap asks for the treasury screen`() {
        var taps = 0
        compose.setContent {
            TreasuryBanner(
                overdraftOn = banner.overdraftOn,
                balanceBeforeSalaryCents = banner.balanceBeforeSalaryCents,
                balanceBeforeSalaryOn = banner.balanceBeforeSalaryOn,
                onClick = { taps++ },
            )
        }

        compose.onNodeWithTag(UiTags.TREASURY_BANNER).performClick()

        assertEquals(1, taps)
    }

    private fun dashboardWith(outlook: TreasuryOutlook) {
        compose.setContent {
            FinanceDashboardScreen(
                monthBalanceViewModel = FakeMonthBalance().viewModel,
                upcomingViewModel = FakeUpcoming().viewModel,
                viewModel = FakeLoadedFinanceDashboard(FinanceDashboard(year = 2026, month = 10), outlook).viewModel,
                onOpen = { opened += it },
            )
        }
    }

    private val opened = mutableListOf<String>()

    @Test
    fun `the dashboard draws the banner when an overdraft is forecast, and a tap opens the treasury`() {
        dashboardWith(
            TreasuryOutlook(
                overdraft = TreasuryOverdraft(isForecast = true, onDate = banner.overdraftOn),
                balanceBeforeSalary = BalanceBeforeSalary(banner.balanceBeforeSalaryOn, banner.balanceBeforeSalaryCents),
            ),
        )

        compose.onNodeWithTag(UiTags.TREASURY_BANNER).assertIsDisplayed().performClick()

        assertEquals(listOf(Screen.Treasury.route), opened)
    }

    @Test
    fun `the dashboard draws no banner without a forecast overdraft`() {
        dashboardWith(TreasuryOutlook())

        compose.onNodeWithTag(UiTags.TREASURY_BANNER).assertDoesNotExist()
    }
}

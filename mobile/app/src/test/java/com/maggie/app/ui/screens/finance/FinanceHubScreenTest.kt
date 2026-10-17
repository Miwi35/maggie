package com.maggie.app.ui.screens.finance

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.screentest.FakeMonthBalance
import com.maggie.app.screentest.FakeUpcoming
import com.maggie.app.screentest.FakeFinanceDashboard
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.components.AppDrawerContent
import com.maggie.app.ui.navigation.FinanceMenu
import com.maggie.app.ui.navigation.Screen
import com.maggie.app.screentest.tap
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * One « Finance » entry in the drawer, and the dashboard as the module's front
 * door (MAG-196, MAG-426) — what `06-finance-hub` walked on an emulator (MAG-242).
 *
 * `FinanceNavigationTest` beside this one already says what the module's
 * navigation *is*. What it cannot say is that any of it is drawn, and that is the
 * half the journey was paying an emulator for. So this is deliberately thin: the
 * drawer entry is on screen and asks for the dashboard; the menus themselves are
 * `FinanceMenusScreenTest`'s.
 */
@RunWith(AndroidJUnit4::class)
class FinanceHubScreenTest {

    @get:Rule
    val compose = ScreenRule()

    @Test
    fun `the drawer offers Finance and not the parts of the module`() {
        compose.setContent {
            AppDrawerContent(currentRoute = "dashboard", onNavigate = {}, onCloseDrawer = {})
        }

        compose.onNodeWithTag(UiTags.drawerItem(Screen.FinanceDashboard.route)).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.drawerItem(Screen.BudgetList.route)).assertDoesNotExist()
        compose.onNodeWithTag(UiTags.drawerItem(Screen.AccountList.route)).assertDoesNotExist()
    }

    @Test
    fun `the drawer entry opens the finance dashboard`() {
        val opened = mutableListOf<String>()
        compose.setContent {
            AppDrawerContent(currentRoute = "dashboard", onNavigate = { opened += it }, onCloseDrawer = {})
        }

        // `tap()` and not `performClick()`: a `ModalDrawerSheet` takes no injected touch.
        compose.onNodeWithTag(UiTags.drawerItem(Screen.FinanceDashboard.route)).tap()

        assertEquals(listOf(Screen.FinanceDashboard.route), opened)
    }

    /**
     * The dashboard is no longer a list of accesses: the menus are the tabs above it
     * (`FinanceMenusScreenTest`), and what is below is the figures. Over a month the API
     * could not serve, none of the former accesses is drawn — and the screen still answers.
     */
    @Test
    fun `the dashboard draws no list of accesses of its own`() {
        compose.setContent {
            FinanceDashboardScreen(
                monthBalanceViewModel = FakeMonthBalance().viewModel,
                upcomingViewModel = FakeUpcoming().viewModel,
                viewModel = FakeFinanceDashboard().viewModel,
                onBack = {},
                onOpen = {},
            )
        }

        FinanceMenu.entries.flatMap { it.routes }.forEach {
            compose.onNodeWithTag(UiTags.financeBlock(it)).assertDoesNotExist()
        }
    }

    @Test
    fun `a month the server could not compute says so instead of staying empty`() {
        compose.setContent {
            FinanceDashboardScreen(
                monthBalanceViewModel = FakeMonthBalance().viewModel,
                upcomingViewModel = FakeUpcoming().viewModel,
                viewModel = FakeFinanceDashboard(error = "Service indisponible").viewModel,
                onBack = {},
                onOpen = {},
            )
        }

        compose.onNodeWithText("Service indisponible").performScrollTo().assertIsDisplayed()
    }

    /** The back arrow leaves the module; where it lands is `financeBackAction`'s, next door. */
    @Test
    fun `the back arrow asks to leave the dashboard`() {
        var backs = 0
        compose.setContent {
            FinanceDashboardScreen(
                monthBalanceViewModel = FakeMonthBalance().viewModel,
                upcomingViewModel = FakeUpcoming().viewModel,
                viewModel = FakeFinanceDashboard().viewModel,
                onBack = { backs++ },
                onOpen = {},
            )
        }

        compose.onNodeWithContentDescription("Retour").performClick()

        assertEquals(1, backs)
    }
}

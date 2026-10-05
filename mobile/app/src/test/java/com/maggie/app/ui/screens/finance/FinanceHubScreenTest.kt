package com.maggie.app.ui.screens.finance

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.screentest.FakeFinanceDashboard
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.components.AppDrawerContent
import com.maggie.app.ui.navigation.FINANCE_ACCESSES
import com.maggie.app.ui.navigation.Screen
import com.maggie.app.screentest.tap
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * One « Finance » entry in the drawer, and the dashboard leads to the rest
 * (MAG-196) — what `06-finance-hub` walked on an emulator (MAG-242).
 *
 * `FinanceNavigationTest` beside this one already says what the module's
 * navigation *is*: one drawer entry, every part one tap from the dashboard, back
 * returning to it. What it cannot say is that any of it is drawn — a constant can
 * be right while the dashboard renders none of it — and that is the half the
 * journey was paying an emulator for. So this is deliberately thin: the entries
 * are on screen, and tapping one asks for the route it names.
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
     * Every access, and over a month the API could not serve: the figures are one
     * card and the accesses are another, so a dashboard that failed to load must
     * still be the door to the module.
     */
    @Test
    fun `the dashboard draws an access to every part of the module, even when the figures failed`() {
        val opened = mutableListOf<String>()
        compose.setContent {
            FinanceDashboardScreen(
                viewModel = FakeFinanceDashboard().viewModel,
                onBack = {},
                onOpen = { opened += it },
            )
        }

        FINANCE_ACCESSES.forEach { access ->
            compose.onNodeWithTag(UiTags.financeAccess(access.route))
                .performScrollTo()
                .assertIsDisplayed()
        }

        compose.onNodeWithTag(UiTags.financeAccess(Screen.BudgetList.route)).performClick()

        assertEquals(listOf(Screen.BudgetList.route), opened)
    }

    @Test
    fun `a month the server could not compute says so instead of staying empty`() {
        compose.setContent {
            FinanceDashboardScreen(
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
                viewModel = FakeFinanceDashboard().viewModel,
                onBack = { backs++ },
                onOpen = {},
            )
        }

        compose.onNodeWithContentDescription("Retour").performClick()

        assertEquals(1, backs)
    }
}

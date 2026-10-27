package com.maggie.app.ui.screens.finance

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.screentest.FakeUpcoming
import com.maggie.app.screentest.FakeFinanceDashboard
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.components.AppDrawerContent
import com.maggie.app.ui.navigation.Screen
import com.maggie.app.screentest.tap
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * One « Finance » entry in the drawer, and the dashboard as the module's front
 * door (MAG-196, MAG-426, MAG-537) — what `06-finance-hub` walked on an emulator (MAG-242).
 *
 * `FinanceNavigationTest` beside this one already says what the module's
 * navigation *is*. What it cannot say is that any of it is drawn, and that is the
 * half the journey was paying an emulator for. So this is deliberately thin: the
 * drawer entry is on screen and asks for the dashboard; the header and its sections are
 * `FinanceTopBarScreenTest`'s.
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
     * The dashboard is no list of accesses: the sections are the breadcrumb's menu (`FinanceTopBarScreenTest`),
     * and what is below is the figures. Over a month the API could not serve, the header is still there.
     */
    @Test
    fun `the dashboard wears the breadcrumb and its two sub-views, and no list of accesses`() {
        compose.setContent {
            FinanceDashboardScreen(
                upcomingViewModel = FakeUpcoming().viewModel,
                viewModel = FakeFinanceDashboard().viewModel,
                onOpen = {},
            )
        }

        compose.onNodeWithTag(UiTags.BREADCRUMB_MODULE).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.BREADCRUMB_SECTION).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.subview("current")).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.subview("upcoming")).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.subview("projects")).assertDoesNotExist()
    }

    @Test
    fun `a month the server could not compute says so instead of staying empty`() {
        compose.setContent {
            FinanceDashboardScreen(
                upcomingViewModel = FakeUpcoming().viewModel,
                viewModel = FakeFinanceDashboard(error = "Service indisponible").viewModel,
                onOpen = {},
            )
        }

        compose.onAllNodesWithText("Service indisponible").onFirst().assertIsDisplayed()
    }
}

package com.maggie.app.ui.navigation

import com.maggie.app.ui.UiTags
import com.maggie.app.ui.components.DRAWER_DESTINATIONS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FinanceNavigationTest {
    private val legacyDrawerRoutes = listOf(
        Screen.AccountList, Screen.CategoryList, Screen.BudgetList,
        Screen.Cushion, Screen.LoanList, Screen.MonthlyReview,
    ).map { it.route }

    private val allRoutes = FinanceMenu.entries.flatMap { it.routes }

    @Test
    fun `the drawer has one finance entry and it opens the finance dashboard`() {
        val finance = DRAWER_DESTINATIONS.filter { it.label == "Finance" }

        assertEquals(listOf(Screen.FinanceDashboard.route), finance.map { it.route })
        assertTrue(DRAWER_DESTINATIONS.none { it.route in legacyDrawerRoutes })
    }

    @Test
    fun `there are seven menus, in the order of the admin`() {
        assertEquals(
            listOf("dashboard", "accounts", "review", "budget", "treasury", "planning", "settings"),
            FinanceMenu.entries.map { it.key },
        )
        assertEquals(
            listOf("Dashboard", "Comptes", "Revue", "Budget", "Trésorerie", "Planification", "Configuration"),
            FinanceMenu.entries.map { it.label },
        )
    }

    @Test
    fun `every part of the finance module belongs to exactly one menu`() {
        assertTrue(allRoutes.containsAll(legacyDrawerRoutes))
        assertTrue(Screen.CategorizationRuleList.route in allRoutes)
        assertTrue(Screen.BankConnectionList.route in allRoutes)
        assertTrue(Screen.RecurringOperations.route in allRoutes)
        assertTrue(Screen.Treasury.route in allRoutes)
        assertTrue(Screen.SavingsAdvances.route in allRoutes)
        assertEquals(allRoutes.size, allRoutes.toSet().size)
    }

    @Test
    fun `a menu opens on its own first route`() {
        FinanceMenu.entries.forEach { assertEquals(it.routes.first(), it.home) }
        assertEquals(Screen.FinanceDashboard.route, FinanceMenu.DASHBOARD.home)
        assertEquals(Screen.AccountList.route, FinanceMenu.ACCOUNTS.home)
        assertEquals(Screen.BudgetList.route, FinanceMenu.BUDGET.home)
    }

    @Test
    fun `the menus without a screen of their own open a page of tabs`() {
        assertEquals(Screen.FinanceReview.route, FinanceMenu.REVIEW.home)
        assertEquals(Screen.FinanceTreasuryMenu.route, FinanceMenu.TREASURY.home)
        assertEquals(Screen.FinancePlanning.route, FinanceMenu.PLANNING.home)
        assertEquals(Screen.FinanceConfiguration.route, FinanceMenu.SETTINGS.home)
    }

    @Test
    fun `a screen is found under the menu it belongs to`() {
        assertEquals(FinanceMenu.TREASURY, FinanceMenu.of(Screen.Cushion.route))
        assertEquals(FinanceMenu.TREASURY, FinanceMenu.of(Screen.SavingsAdvances.route))
        assertEquals(FinanceMenu.REVIEW, FinanceMenu.of(Screen.RecurringOperations.route))
        assertEquals(FinanceMenu.PLANNING, FinanceMenu.of(Screen.LoanList.route))
        assertEquals(FinanceMenu.SETTINGS, FinanceMenu.of(Screen.CategoryList.route))
        assertEquals(FinanceMenu.ACCOUNTS, FinanceMenu.of(Screen.AccountTransactions.route))
        assertEquals(FinanceMenu.ACCOUNTS, FinanceMenu.of(Screen.Contacts.route))
        assertEquals(FinanceMenu.ACCOUNTS, FinanceMenu.of(Screen.PeerDebts.route))
        assertNull(FinanceMenu.of(Screen.Dashboard.route))
        assertNull(FinanceMenu.of(null))
    }

    @Test
    fun `every part can still be opened from outside`() {
        val withoutLink = setOf(
            Screen.FinanceReview.route,
            Screen.FinanceTreasuryMenu.route,
            Screen.FinancePlanning.route,
            Screen.FinanceConfiguration.route,
            Screen.AccountTransactions.route,
        )

        allRoutes.filter { it !in withoutLink }.forEach {
            assertTrue("$it lost its deep link", DeepLinks.patternsFor(it).isNotEmpty())
        }
    }

    @Test
    fun `the recognised lines to control sit in the review menu right after the lines to qualify, with a deep link`() {
        assertEquals("recognised_to_control", Screen.RecognisedToControl.route)
        assertEquals("Reconnues à contrôler", Screen.RecognisedToControl.label)
        assertEquals(FinanceMenu.REVIEW, FinanceMenu.of(Screen.RecognisedToControl.route))
        assertEquals(
            listOf("${DeepLinks.SCHEME}://finance/to-control"),
            DeepLinks.patternsFor(Screen.RecognisedToControl.route),
        )

        val routes = FinanceMenu.REVIEW.routes
        assertEquals(routes.indexOf(Screen.TransactionsToSort.route) + 1, routes.indexOf(Screen.RecognisedToControl.route))
    }

    @Test
    fun `the lines to qualify keep their route and deep link under their new name`() {
        assertEquals("transactions_to_sort", Screen.TransactionsToSort.route)
        assertEquals("À qualifier", Screen.TransactionsToSort.label)
        assertTrue(DeepLinks.patternsFor(Screen.TransactionsToSort.route).isNotEmpty())
    }

    @Test
    fun `a tap on a menu goes to its home and, with the dashboard underneath, back to it`() {
        FinanceMenu.entries.forEach { menu ->
            assertEquals(FinanceTabAction(menu.home, popToDashboard = true), financeTabAction(menu, dashboardInBackStack = true))
        }
    }

    @Test
    fun `a tap on a menu replaces the current screen when the dashboard is not underneath`() {
        assertEquals(
            FinanceTabAction(Screen.FinanceReview.route, popToDashboard = false),
            financeTabAction(FinanceMenu.REVIEW, dashboardInBackStack = false),
        )
    }

    @Test
    fun `back goes to the dashboard it was opened from`() {
        assertEquals(FinanceBack.POP, financeBackAction(Screen.FinanceDashboard.route))
    }

    @Test
    fun `back goes to the finance list or menu page a screen belongs to`() {
        assertEquals(FinanceBack.POP, financeBackAction(Screen.AccountList.route))
        assertEquals(FinanceBack.POP, financeBackAction(Screen.CategoryList.route))
        assertEquals(FinanceBack.POP, financeBackAction(Screen.FinanceTreasuryMenu.route))
        assertEquals(FinanceBack.POP, financeBackAction(Screen.FinanceConfiguration.route))
        // Suggestions are opened from the rules screen, which is where back goes.
        assertEquals(FinanceBack.POP, financeBackAction(Screen.CategorizationRuleList.route))
        assertEquals(FinanceBack.POP, financeBackAction(Screen.RuleSuggestions.route))
    }

    @Test
    fun `back from the recurring operations goes to the dashboard, or to the account its transaction came from`() {
        assertEquals(FinanceBack.POP, financeBackAction(Screen.FinanceDashboard.route))
        assertEquals(FinanceBack.POP, financeBackAction(Screen.AccountTransactions.route))
        assertEquals(FinanceBack.POP, financeBackAction(Screen.AccountList.route))
    }

    @Test
    fun `a screen opened by a link is swapped for the dashboard on back`() {
        assertEquals(FinanceBack.REPLACE_WITH_DASHBOARD, financeBackAction(Screen.Dashboard.route))
        assertEquals(FinanceBack.REPLACE_WITH_DASHBOARD, financeBackAction(Screen.Loading.route))
        assertEquals(FinanceBack.REPLACE_WITH_DASHBOARD, financeBackAction(null))
    }

    @Test
    fun `each menu and each aspect tab has its own tag`() {
        assertEquals("finance_menu_treasury", UiTags.financeMenu(FinanceMenu.TREASURY.key))
        assertEquals("finance_tab_aspect_cushion", UiTags.financeTab("cushion"))
    }

    @Test
    fun `a menu page registered with its tab argument is still found under its menu`() {
        assertEquals("finance_review?tab={tab}", financeMenuPattern(Screen.FinanceReview.route))
        assertEquals(FinanceMenu.REVIEW, FinanceMenu.of(financeMenuPattern(Screen.FinanceReview.route)))
        assertEquals(FinanceMenu.SETTINGS, FinanceMenu.of("finance_configuration?tab=banks"))
        assertNull(FinanceMenu.of("somewhere_else?tab=banks"))
    }

    @Test
    fun `back from a screen over a menu page with a tab goes back to it`() {
        assertEquals(FinanceBack.POP, financeBackAction(financeMenuPattern(Screen.FinanceTreasuryMenu.route)))
        assertEquals(FinanceBack.REPLACE_WITH_DASHBOARD, financeBackAction("somewhere_else?tab={tab}"))
    }

    @Test
    fun `a tap on a menu can ask for one of its tabs`() {
        assertEquals(
            FinanceTabAction("finance_review?tab=rules", popToDashboard = true),
            financeTabAction(FinanceMenu.REVIEW, dashboardInBackStack = true, tab = "rules"),
        )
        assertEquals(
            FinanceTabAction(Screen.FinanceReview.route, popToDashboard = true),
            financeTabAction(FinanceMenu.REVIEW, dashboardInBackStack = true),
        )
    }
}

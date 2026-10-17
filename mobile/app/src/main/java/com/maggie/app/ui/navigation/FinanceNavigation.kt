package com.maggie.app.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The seven Finance menus (owner, 15 Oct.; spec « Sept menus Finance, peu de pages »).
 *
 * Same names and same grouping as the admin's side menu. A menu lists only the screens
 * that exist: nothing here is a placeholder, and a screen that arrives with its ticket is
 * added to its menu's [routes] and nowhere else. The first route is the menu's [home].
 *
 * A menu never opens a list of links: its home is either the screen itself (Dashboard,
 * Comptes, Budget) or a summary page with one block per aspect (the four `Finance…` routes).
 */
enum class FinanceMenu(
    val key: String,
    val label: String,
    val icon: ImageVector,
    val routes: List<String>,
) {
    DASHBOARD(
        "dashboard", "Dashboard", Icons.Default.Dashboard,
        listOf(Screen.FinanceDashboard.route),
    ),
    ACCOUNTS(
        "accounts", "Comptes", Icons.Default.AccountBalance,
        listOf(
            Screen.AccountList.route,
            Screen.AccountTransactions.route,
            Screen.Contacts.route,
            Screen.PeerDebts.route,
        ),
    ),
    REVIEW(
        "review", "Revue", Icons.AutoMirrored.Filled.FactCheck,
        listOf(
            Screen.FinanceReview.route,
            Screen.TransactionsToSort.route,
            Screen.MonthlyReview.route,
            Screen.CategorizationRuleList.route,
            Screen.RuleSuggestions.route,
            Screen.RecurringOperations.route,
        ),
    ),
    BUDGET(
        "budget", "Budget", Icons.Default.AccountBalanceWallet,
        listOf(Screen.BudgetList.route),
    ),
    TREASURY(
        "treasury", "Trésorerie", Icons.Default.Savings,
        listOf(
            Screen.FinanceTreasuryMenu.route,
            Screen.Treasury.route,
            Screen.Cushion.route,
            Screen.SavingsAdvances.route,
        ),
    ),
    PLANNING(
        "planning", "Planification", Icons.Default.Timeline,
        listOf(Screen.FinancePlanning.route, Screen.LoanList.route),
    ),
    SETTINGS(
        "settings", "Configuration", Icons.Default.Settings,
        listOf(
            Screen.FinanceConfiguration.route,
            Screen.BankConnectionList.route,
            Screen.CategoryList.route,
        ),
    ),
    ;

    val home: String get() = routes.first()

    companion object {
        /** The menu a finance route belongs to, `null` for a route outside the module. */
        fun of(route: String?): FinanceMenu? = entries.firstOrNull { route in it.routes }
    }
}

private val FINANCE_ROUTES: Set<String> = FinanceMenu.entries.flatMap { it.routes }.toSet()

enum class FinanceBack { POP, REPLACE_WITH_DASHBOARD }

/**
 * A finance screen goes back to the screen it was opened from when that is a finance
 * screen (the dashboard, or the list a detail belongs to). Opened from outside — a
 * notification, a link — it has no finance screen under it, so it is swapped for the
 * dashboard instead of leaving the finance module.
 */
internal fun financeBackAction(previousRoute: String?): FinanceBack =
    if (previousRoute in FINANCE_ROUTES) FinanceBack.POP else FinanceBack.REPLACE_WITH_DASHBOARD

/**
 * What a tap on a menu does to the back stack.
 *
 * The menus are siblings, not a trail: Dashboard → Trésorerie → Revue goes back to the
 * Dashboard in one step, not through Trésorerie. With the dashboard underneath, everything
 * above it goes; without it (the module was opened by a link on a deeper screen) the
 * current screen is the one replaced, and its back swaps in the dashboard like any other.
 */
internal data class FinanceTabAction(val route: String, val popToDashboard: Boolean)

internal fun financeTabAction(target: FinanceMenu, dashboardInBackStack: Boolean): FinanceTabAction =
    FinanceTabAction(route = target.home, popToDashboard = dashboardInBackStack)

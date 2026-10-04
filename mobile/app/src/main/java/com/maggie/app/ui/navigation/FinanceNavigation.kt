package com.maggie.app.ui.navigation

enum class FinanceFrequency(val title: String?) {
    DAILY(null),
    MONTHLY("Chaque mois"),
    RARE("Réglages finance"),
}

data class FinanceAccess(val screen: Screen, val label: String, val frequency: FinanceFrequency) {
    val route: String get() = screen.route
}

// Most used first: the dashboard lists them in this order (spec: finance functional spec, « Navigation mobile »).
val FINANCE_ACCESSES = listOf(
    FinanceAccess(Screen.BudgetList, "Budgets et enveloppes", FinanceFrequency.DAILY),
    FinanceAccess(Screen.AccountList, "Comptes et transactions", FinanceFrequency.DAILY),
    FinanceAccess(Screen.MonthlyReview, "Revue mensuelle", FinanceFrequency.MONTHLY),
    FinanceAccess(Screen.Cushion, "Matelas", FinanceFrequency.MONTHLY),
    FinanceAccess(Screen.LoanList, "Prêts", FinanceFrequency.RARE),
    FinanceAccess(Screen.CategoryList, "Catégories", FinanceFrequency.RARE),
    FinanceAccess(Screen.CategorizationRuleList, "Règles de catégorisation", FinanceFrequency.RARE),
)

internal fun financeAccessesBy(frequency: FinanceFrequency): List<FinanceAccess> =
    FINANCE_ACCESSES.filter { it.frequency == frequency }

private val FINANCE_ROUTES: Set<String> =
    FINANCE_ACCESSES.map { it.route }.toSet() +
        setOf(Screen.FinanceDashboard.route, Screen.AccountTransactions.route)

enum class FinanceBack { POP, REPLACE_WITH_DASHBOARD }

/**
 * A finance screen goes back to the screen it was opened from when that is a finance
 * screen (the dashboard, or the list a detail belongs to). Opened from outside — a
 * notification, a link — it has no finance screen under it, so it is swapped for the
 * dashboard instead of leaving the finance module.
 */
internal fun financeBackAction(previousRoute: String?): FinanceBack =
    if (previousRoute in FINANCE_ROUTES) FinanceBack.POP else FinanceBack.REPLACE_WITH_DASHBOARD

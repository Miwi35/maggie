package com.maggie.app.ui.navigation

import androidx.navigation.NavDeepLink
import androidx.navigation.navDeepLink
import com.maggie.app.BuildConfig

/**
 * What the outside world can open: `maggie://…` links, from a notification, a widget or a shortcut.
 *
 * A link either lands on a screen that stands alone (finance, chat), or names an entity
 * (event, task, grocery item, recipe) that a `link/…` route resolves first, then replaces itself
 * with the screen that shows it.
 */
object DeepLinks {
    // `maggie` in prod; dev and e2e have their own so the three can be installed together.
    val SCHEME: String = BuildConfig.DEEP_LINK_SCHEME

    const val ARG_ID = "id"
    const val ARG_MESSAGE = "message"
    const val ARG_NAME = "name"

    const val EVENT_ROUTE = "link/event/{$ARG_ID}"
    const val TASK_ROUTE = "link/task/{$ARG_ID}"
    const val GROCERY_ROUTE = "link/grocery/{$ARG_ID}"
    const val RECIPE_ROUTE = "link/recipe/{$ARG_ID}"
    const val ACCOUNT_ROUTE = "link/finance/accounts/{$ARG_ID}?$ARG_NAME={$ARG_NAME}"

    private val PATTERNS: Map<String, List<String>> = mapOf(
        EVENT_ROUTE to listOf("$SCHEME://event/{$ARG_ID}"),
        TASK_ROUTE to listOf("$SCHEME://task/{$ARG_ID}"),
        GROCERY_ROUTE to listOf("$SCHEME://grocery/{$ARG_ID}"),
        RECIPE_ROUTE to listOf("$SCHEME://recipe/{$ARG_ID}"),
        ACCOUNT_ROUTE to listOf("$SCHEME://finance/accounts/{$ARG_ID}?$ARG_NAME={$ARG_NAME}"),
        Screen.FinanceDashboard.route to listOf("$SCHEME://finance"),
        Screen.AccountList.route to listOf("$SCHEME://finance/accounts"),
        Screen.BudgetList.route to listOf("$SCHEME://finance/budgets"),
        Screen.CategoryList.route to listOf("$SCHEME://finance/categories"),
        Screen.CategorizationRuleList.route to listOf("$SCHEME://finance/rules"),
        Screen.RuleSuggestions.route to listOf("$SCHEME://finance/rule-suggestions"),
        Screen.BankConnectionList.route to listOf("$SCHEME://finance/banks"),
        Screen.Cushion.route to listOf("$SCHEME://finance/cushion"),
        Screen.LoanList.route to listOf("$SCHEME://finance/loans"),
        Screen.MonthlyReview.route to listOf("$SCHEME://finance/review"),
        Screen.Chat.route to listOf("$SCHEME://chat?$ARG_MESSAGE={$ARG_MESSAGE}"),
    )

    val ROUTES: Set<String> = PATTERNS.keys

    fun patternsFor(route: String): List<String> = PATTERNS[route].orEmpty()

    fun forRoute(route: String): List<NavDeepLink> =
        patternsFor(route).map { pattern -> navDeepLink { uriPattern = pattern } }

    // Ids go into API paths: only what a ULID or a UUID is made of.
    private val VALID_ID = Regex("[0-9A-Za-z-]{1,64}")

    fun isValidId(id: String?): Boolean = id != null && VALID_ID.matches(id)

    // A link prefills the chat, it never sends: any app can fire one.
    fun chatDraft(message: String?): String = message?.trim().orEmpty()
}

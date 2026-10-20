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

    /** A task's Focus (MAG-510), what « Je m'y mets » of a reminder opens. */
    const val TASK_FOCUS_ROUTE = "link/task/{$ARG_ID}/focus"
    const val GROCERY_ROUTE = "link/grocery/{$ARG_ID}"
    const val RECIPE_ROUTE = "link/recipe/{$ARG_ID}"
    const val ACCOUNT_ROUTE = "link/finance/accounts/{$ARG_ID}?$ARG_NAME={$ARG_NAME}"
    const val CONTACT_ROUTE = "link/finance/contacts/{$ARG_ID}"

    /**
     * The three links the Revue's pages used to have (MAG-539): each opens the one review list, already filtered
     * (to-sort: À qualifier · Toutes les dates, to-control: Reconnues · Depuis la dernière revue, review: Toutes · ce mois).
     */
    const val REVIEW_TO_SORT_ROUTE = "link/finance/to-sort"
    const val REVIEW_TO_CONTROL_ROUTE = "link/finance/to-control"
    const val REVIEW_MONTHLY_ROUTE = "link/finance/review"

    private val PATTERNS: Map<String, List<String>> = mapOf(
        EVENT_ROUTE to listOf("$SCHEME://event/{$ARG_ID}"),
        TASK_ROUTE to listOf("$SCHEME://task/{$ARG_ID}"),
        TASK_FOCUS_ROUTE to listOf("$SCHEME://task/{$ARG_ID}/focus"),
        GROCERY_ROUTE to listOf("$SCHEME://grocery/{$ARG_ID}"),
        RECIPE_ROUTE to listOf("$SCHEME://recipe/{$ARG_ID}"),
        ACCOUNT_ROUTE to listOf("$SCHEME://finance/accounts/{$ARG_ID}?$ARG_NAME={$ARG_NAME}"),
        // The tasks screen (MAG-512): what a routine reminder's « Voir » opens.
        Screen.Tasks.route to listOf("$SCHEME://tasks"),
        Screen.FinanceDashboard.route to listOf("$SCHEME://finance"),
        CONTACT_ROUTE to listOf("$SCHEME://finance/contacts/{$ARG_ID}"),
        Screen.AccountList.route to listOf("$SCHEME://finance/accounts"),
        Screen.Contacts.route to listOf("$SCHEME://finance/contacts"),
        Screen.BudgetList.route to listOf("$SCHEME://finance/budgets"),
        Screen.CategoryList.route to listOf("$SCHEME://finance/categories"),
        Screen.CategorizationRuleList.route to listOf("$SCHEME://finance/rules"),
        Screen.RuleSuggestions.route to listOf("$SCHEME://finance/rule-suggestions"),
        REVIEW_TO_SORT_ROUTE to listOf("$SCHEME://finance/to-sort"),
        REVIEW_TO_CONTROL_ROUTE to listOf("$SCHEME://finance/to-control"),
        REVIEW_MONTHLY_ROUTE to listOf("$SCHEME://finance/review"),
        Screen.ReviewList.route to listOf("$SCHEME://finance/review/list"),
        Screen.RecurringOperations.route to listOf("$SCHEME://finance/recurring-operations"),
        Screen.BankConnectionList.route to listOf("$SCHEME://finance/banks"),
        Screen.Cushion.route to listOf("$SCHEME://finance/cushion"),
        Screen.Treasury.route to listOf("$SCHEME://finance/treasury"),
        Screen.SavingsAdvances.route to listOf("$SCHEME://finance/savings-advances"),
        Screen.PeerDebts.route to listOf("$SCHEME://finance/peer-debts"),
        Screen.LoanList.route to listOf("$SCHEME://finance/loans"),
        Screen.ProjectList.route to listOf("$SCHEME://finance/projects"),
        Screen.PlacementList.route to listOf("$SCHEME://finance/placements"),
        Screen.FinanceReviewActivity.route to listOf("$SCHEME://finance/review/activity"),
        Screen.Chat.route to listOf("$SCHEME://chat?$ARG_MESSAGE={$ARG_MESSAGE}"),
    )

    val ROUTES: Set<String> = PATTERNS.keys

    fun patternsFor(route: String): List<String> = PATTERNS[route].orEmpty()

    fun forRoute(route: String): List<NavDeepLink> =
        patternsFor(route).map { pattern -> navDeepLink { uriPattern = pattern } }

    // Ids go into API paths: only what a ULID or a UUID is made of.
    private val VALID_ID = Regex("[0-9A-Za-z-]{1,64}")

    fun isValidId(id: String?): Boolean = id != null && VALID_ID.matches(id)

    /** The link of [taskId]'s Focus; the id is the caller's to check ([isValidId]). */
    fun taskFocus(taskId: String): String = "$SCHEME://task/$taskId/focus"

    // A link prefills the chat, it never sends: any app can fire one.
    fun chatDraft(message: String?): String = message?.trim().orEmpty()
}

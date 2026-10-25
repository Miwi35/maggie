package com.maggie.app.data.mercure

/**
 * How a Mercure topic is spelled, on the mobile side.
 *
 * The API publishes to `/users/{userId}/api/{resource}/{id}` — see
 * `Maggie\Core\Mercure\MercureTopic`, which writes the full list to
 * `api/contract/mercure-topics.json`. A subscription that does not match that
 * string byte for byte receives nothing at all: no error, no reconnect, no
 * log. The screen simply never refreshes.
 *
 * That is not a hypothetical. Before this object existed, GroceryViewModel
 * subscribed to the literal `"/users/{userId}/api/grocery_lists/{id}"` — the
 * placeholder was never substituted, because MercureService passes the topic
 * straight through — and RecipeListViewModel subscribed to
 * `"/api/recipes/{id}"`, with no user scope at all. Both had been dead for as
 * long as they had existed.
 *
 * MercureTopicsContractTest checks what this builds against the API's and the
 * agent's published contracts, and checks that no ViewModel bypasses it.
 */
object MercureTopics {

    /**
     * `{id}` stays literal: MercureService subscribes it as the URL Pattern
     * `:id`, matching any resource of that collection. `userId` does not —
     * it is the one part the client has to fill in.
     */
    fun userScoped(userId: String, collection: String): String =
        "/users/$userId/api/$collection/{id}"

    /**
     * The agent's topics sit outside `/users/`: `/{stream}/{userId}`, where
     * `userId` is the API user's ULID — the `sub` of the JWT the agent
     * authenticates (`agent/contract/mercure-topics.json`). The `user_id` the
     * app sends to the agent (`"default"`) is ignored by it, so it is never
     * what to subscribe with.
     */
    fun agentScoped(userId: String, stream: String): String = "/$stream/$userId"

    /**
     * What a card of the Accueil refreshes on (MAG-560): the API publishes `/users/{userId}/home/{module}`
     * (`HomeCardPublisher`) each time what the card reads changes. Unlike [userScoped] there is no `/api/`
     * segment and no `{id}`: a mismatch here subscribes without error and never fires. Not in [SUBSCRIBED]:
     * the module is the card's, so the set cannot name it.
     */
    fun homeScoped(userId: String, module: String): String = "/users/$userId/home/$module"

    const val CHAT = "chat"
    const val CONTEXTS = "contexts"
    const val APPROVALS = "approvals"

    const val ACCOUNTS = "accounts"
    const val AGENDAS = "agendas"
    const val AISLES = "aisles"
    const val CATEGORIES = "categories"
    const val COOKING_TIMERS = "cooking/timers"
    const val DAY_PLANS = "day_plans"
    const val ENVELOPES = "envelopes"
    const val ERRANDS = "errands"
    const val EVENTS = "events"
    const val FINANCE_PLACEMENTS = "finance/placements"
    const val FINANCE_PROJECTS = "finance/projects"
    const val FINANCE_SCENARIOS = "finance/scenarios"
    const val GROCERY_LISTS = "grocery_lists"
    const val INGREDIENTS = "ingredients"
    const val LOANS = "loans"
    const val MEALS = "meals"
    const val MEAL_SETTINGS = "meal_settings"
    const val NOTIFICATIONS = "notifications"
    const val PRODUCTS = "products"
    const val PROACTIONS = "proactions"
    const val RECIPES = "recipes"
    const val RECURRING_GROCERY_ITEMS = "recurring_grocery_items"
    const val REWARDS = "rewards"
    const val ROUTINES = "routines"
    const val ROUTINE_CHECKS = "routine_checks"
    const val SAFETY_CUSHIONS = "safety_cushions"
    const val STORES = "stores"
    const val TASKS = "tasks"
    const val TASK_SETTINGS = "task_settings"
    const val TRANSACTIONS = "transactions"
    const val USER_PREFERENCES = "user_preferences"

    /**
     * Every collection the app subscribes to. The contract test checks each
     * one against `api/contract/mercure-topics.json`, so a resource renamed
     * on the API side fails here rather than on a screen that stops
     * refreshing.
     */
    val SUBSCRIBED: Set<String> = setOf(
        ACCOUNTS,
        AGENDAS,
        AISLES,
        CATEGORIES,
        COOKING_TIMERS,
        DAY_PLANS,
        ENVELOPES,
        ERRANDS,
        EVENTS,
        FINANCE_PLACEMENTS,
        FINANCE_PROJECTS,
        FINANCE_SCENARIOS,
        GROCERY_LISTS,
        INGREDIENTS,
        LOANS,
        MEALS,
        MEAL_SETTINGS,
        NOTIFICATIONS,
        PRODUCTS,
        PROACTIONS,
        RECIPES,
        RECURRING_GROCERY_ITEMS,
        REWARDS,
        ROUTINES,
        ROUTINE_CHECKS,
        SAFETY_CUSHIONS,
        STORES,
        TASKS,
        TASK_SETTINGS,
        TRANSACTIONS,
        USER_PREFERENCES,
    )

    /** The agent streams the app subscribes to, checked against `agent/contract/mercure-topics.json`. */
    val SUBSCRIBED_AGENT: Set<String> = setOf(CHAT, CONTEXTS, APPROVALS)
}

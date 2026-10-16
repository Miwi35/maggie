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

    const val CHAT = "chat"
    const val CONTEXTS = "contexts"
    const val APPROVALS = "approvals"

    const val AGENDAS = "agendas"
    const val CATEGORIES = "categories"
    const val DAY_PLANS = "day_plans"
    const val ERRANDS = "errands"
    const val EVENTS = "events"
    const val GROCERY_LISTS = "grocery_lists"
    const val MEALS = "meals"
    const val MEAL_SETTINGS = "meal_settings"
    const val NOTIFICATIONS = "notifications"
    const val PROACTIONS = "proactions"
    const val RECIPES = "recipes"
    const val RECURRING_GROCERY_ITEMS = "recurring_grocery_items"
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
        AGENDAS,
        CATEGORIES,
        DAY_PLANS,
        ERRANDS,
        EVENTS,
        GROCERY_LISTS,
        MEALS,
        MEAL_SETTINGS,
        NOTIFICATIONS,
        PROACTIONS,
        RECIPES,
        RECURRING_GROCERY_ITEMS,
        TASKS,
        TASK_SETTINGS,
        TRANSACTIONS,
        USER_PREFERENCES,
    )

    /** The agent streams the app subscribes to, checked against `agent/contract/mercure-topics.json`. */
    val SUBSCRIBED_AGENT: Set<String> = setOf(CHAT, CONTEXTS, APPROVALS)
}

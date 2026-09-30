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
 * MercureTopicsContractTest checks what this builds against the API's
 * published contract, and checks that no ViewModel bypasses it.
 */
object MercureTopics {

    /**
     * `{id}` stays literal: it is a URI-template placeholder in the topic
     * selector, matching any resource of that collection. `userId` does not —
     * it is the one part the client has to fill in.
     */
    fun userScoped(userId: String, collection: String): String =
        "/users/$userId/api/$collection/{id}"

    const val AGENDAS = "agendas"
    const val EVENTS = "events"
    const val GROCERY_LISTS = "grocery_lists"
    const val NOTIFICATIONS = "notifications"
    const val RECIPES = "recipes"
    const val TASKS = "tasks"
    const val USER_PREFERENCES = "user_preferences"

    /**
     * Every collection the app subscribes to. The contract test checks each
     * one against `api/contract/mercure-topics.json`, so a resource renamed
     * on the API side fails here rather than on a screen that stops
     * refreshing.
     */
    val SUBSCRIBED: Set<String> = setOf(
        AGENDAS,
        EVENTS,
        GROCERY_LISTS,
        NOTIFICATIONS,
        RECIPES,
        TASKS,
        USER_PREFERENCES,
    )
}

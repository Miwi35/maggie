package com.maggie.app.data.model

import kotlinx.serialization.Serializable

/**
 * The Courses card of the Accueil (MAG-563): `GET /api/home/grocery`. The API cuts each shop's list
 * at five lines and says how many are left in [GroceryHomeStore.more]; the card draws what it gets.
 */
@Serializable
data class GroceryHomeState(
    val count: Int = 0,
    /** What the meals of today and tomorrow lack: out or running low, on the list or not. */
    val forRecipes: List<GroceryHomeRecipeNeed> = emptyList(),
    val stores: List<GroceryHomeStore> = emptyList(),
)

@Serializable
data class GroceryHomeRecipeNeed(
    val name: String,
    /** `today` or `tomorrow`. */
    val day: String,
    /** `lunch` or `dinner`. */
    val slot: String,
)

@Serializable
data class GroceryHomeStore(
    /** Null for the lines that have no shop. */
    val id: String? = null,
    val name: String? = null,
    val count: Int = 0,
    val items: List<GroceryHomeLine> = emptyList(),
    val more: Int = 0,
)

@Serializable
data class GroceryHomeLine(
    val id: String,
    val name: String,
    val quantity: Float? = null,
    val unit: String? = null,
    /** What the meals need; null for a packaged line, which is counted in its packagings. */
    val needed: List<Need>? = null,
)

/** « parmesan (ce soir) », « crème fraîche (demain) »: the product, in lower case, and when a meal needs it. */
fun GroceryHomeRecipeNeed.label(): String {
    val moment = when {
        day == "tomorrow" -> "demain"
        slot == "dinner" -> "ce soir"
        else -> "ce midi"
    }
    return "${name.replaceFirstChar { it.lowercase() }} ($moment)"
}

/** « parmesan (ce soir), crème fraîche (demain) ». */
fun List<GroceryHomeRecipeNeed>.recipeNeedsLabel(): String = joinToString(", ") { it.label() }

package com.maggie.app.data.model

import kotlinx.serialization.Serializable
import java.time.LocalDate

/**
 * The Courses card of the Accueil (MAG-563), composed client-side from the open list and what the meals need (MAG-590).
 * Each shop's list is cut at five lines and [GroceryHomeStore.more] says how many are left; the card draws what it gets.
 */
data class GroceryHomeState(
    val count: Int = 0,
    /** What the meals of today and tomorrow lack: out or running low, on the list or not. */
    val forRecipes: List<GroceryHomeRecipeNeed> = emptyList(),
    val stores: List<GroceryHomeStore> = emptyList(),
) {
    /** An empty list and nothing the recipes lack: the card is not drawn (MAG-594). */
    val isEmpty: Boolean get() = count == 0 && forRecipes.isEmpty()

    companion object {
        /** Lines shown per shop; the rest is told as a count. */
        const val ITEMS_PER_STORE = 5

        private val BY_AISLE = compareBy<GroceryItem>(
            { if (it.aisle == null) 1 else 0 },
            { it.aisle?.position ?: 0 },
            { it.aisle?.name.orEmpty() },
            { it.position },
        )

        /**
         * The open lines of [items] (not ticked, not held back until a later [GroceryItem.buyAfter]) by shop in the order
         * of the way through, lines with no shop last; [needs] are what the meals of [today] and tomorrow lack.
         */
        fun from(items: List<GroceryItem>, needs: List<GroceryRecipeNeed>, today: LocalDate): GroceryHomeState {
            val open = items.filter { !it.checked && (it.buyAfter == null || LocalDate.parse(it.buyAfter.take(DATE_LENGTH)) <= today) }
            val stores = open.groupBy { it.store?.id }.values
                .sortedBy { it.first().store?.visitOrder ?: Int.MAX_VALUE }
                .map { group ->
                    val lines = group.sortedWith(BY_AISLE)
                    GroceryHomeStore(
                        id = group.first().store?.id,
                        name = group.first().store?.name,
                        count = lines.size,
                        items = lines.take(ITEMS_PER_STORE).map(GroceryHomeLine::from),
                        more = maxOf(0, lines.size - ITEMS_PER_STORE),
                    )
                }
            return GroceryHomeState(
                count = open.size,
                forRecipes = needs.distinctBy { it.productId }.map {
                    GroceryHomeRecipeNeed(
                        name = it.name,
                        day = (if (it.date == today.toString()) HomeDay.TODAY else HomeDay.TOMORROW).apiValue,
                        slot = it.slot,
                    )
                },
                stores = stores,
            )
        }

        private const val DATE_LENGTH = 10
    }
}

/** One entry of `GET /api/grocery/recipe-needs`: a product a meal needs and the owner is low on (MAG-590). */
@Serializable
data class GroceryRecipeNeed(
    val productId: String,
    val name: String,
    /** `YYYY-MM-DD`. */
    val date: String,
    /** `lunch` or `dinner`. */
    val slot: String,
)

data class GroceryHomeRecipeNeed(
    val name: String,
    /** `today` or `tomorrow`. */
    val day: String,
    /** `lunch` or `dinner`. */
    val slot: String,
)

data class GroceryHomeStore(
    /** Null for the lines that have no shop. */
    val id: String? = null,
    val name: String? = null,
    val count: Int = 0,
    val items: List<GroceryHomeLine> = emptyList(),
    val more: Int = 0,
)

data class GroceryHomeLine(
    val id: String,
    val name: String,
    val quantity: Float? = null,
    val unit: String? = null,
    /** What the meals need; null for a packaged line, which is counted in its packagings. */
    val needed: List<Need>? = null,
) {
    companion object {
        fun from(item: GroceryItem): GroceryHomeLine = GroceryHomeLine(
            id = item.id.orEmpty(),
            name = item.label,
            quantity = item.quantity,
            unit = item.unit?.name?.lowercase(),
            // A packaged line is counted in packagings: that count is what to buy, not the need behind it.
            needed = if (item.product?.packagingUnit != null) null else item.needed,
        )
    }
}

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

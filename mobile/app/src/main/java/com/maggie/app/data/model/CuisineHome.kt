package com.maggie.app.data.model

/**
 * The Cuisine card of the Accueil (MAG-564, MAG-591): the meals of a day, composed from `GET /api/meals` and
 * `GET /api/grocery/recipe-needs`. Both slots are always there, lunch first; an empty one has no meal.
 */
data class CuisineHomeState(
    val slots: List<CuisineHomeSlot> = emptyList(),
) {
    /** No meal planned in any slot: the card is not drawn (MAG-594). */
    val isEmpty: Boolean get() = slots.all { it.meals.isEmpty() }

    companion object {
        private val SLOTS = listOf(MealSlot.LUNCH, MealSlot.DINNER)

        /** The meals of one day and what their recipes lack, each product once per meal. */
        fun from(meals: List<Meal>, needs: List<GroceryRecipeNeed>): CuisineHomeState {
            val missing = needs.groupBy { it.mealId }.mapValues { (_, lines) -> lines.distinctBy { it.productId }.map { it.name } }
            return CuisineHomeState(
                slots = SLOTS.map { slot ->
                    CuisineHomeSlot(
                        slot = slot.name.lowercase(),
                        meals = meals.filter { it.slot == slot }.map { meal ->
                            CuisineHomeMeal(
                                id = meal.id,
                                title = meal.summary,
                                guests = meal.guests,
                                recipes = meal.recipeNames,
                                missing = missing[meal.id].orEmpty(),
                            )
                        },
                    )
                },
            )
        }
    }
}

data class CuisineHomeSlot(
    /** `lunch` or `dinner`. */
    val slot: String,
    val meals: List<CuisineHomeMeal> = emptyList(),
)

data class CuisineHomeMeal(
    val id: String,
    val title: String = "",
    val guests: Int = 1,
    /** The names of the meal's recipes. */
    val recipes: List<String> = emptyList(),
    /** The products its recipes lack (low or out of stock), once each. */
    val missing: List<String> = emptyList(),
)

/** All the meal's recipes on one line, « Pâtes · Salade »; the meal's own title when it has none. */
fun CuisineHomeMeal.recipesLabel(): String =
    recipes.takeIf { it.isNotEmpty() }?.joinToString(" · ") ?: title

/** « manque : parmesan, basilic », or null when nothing is missing. */
fun CuisineHomeMeal.missingLabel(): String? =
    missing.takeIf { it.isNotEmpty() }
        ?.joinToString(", ", prefix = "manque : ") { it.replaceFirstChar { c -> c.lowercase() } }

fun CuisineHomeSlot.label(): String = when (slot) {
    "lunch" -> "Déjeuner"
    "dinner" -> "Dîner"
    else -> slot
}

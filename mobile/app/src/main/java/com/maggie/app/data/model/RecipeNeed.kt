package com.maggie.app.data.model

import kotlinx.serialization.Serializable

/**
 * What the meals planned over the next days need of a product (MAG-573), read by the add sheet
 * before the line is added. [recipeNeed] is the one figure to show (in the unit of the packaging's
 * content), [needs] every unit it is asked in, [meals] the detail meal by meal.
 */
@Serializable
data class RecipeNeedResponse(
    val productId: String = "",
    val days: Int = 7,
    val recipeNeed: Need? = null,
    val needs: List<Need> = emptyList(),
    val meals: List<RecipeNeedMeal> = emptyList(),
)

/** One meal asking for the product: « Riz au curry », its day (`yyyy-MM-dd`) and its quantity in base units. */
@Serializable
data class RecipeNeedMeal(
    val mealId: String = "",
    val name: String,
    val date: String = "",
    val quantity: Double,
    val unit: String? = null,
)

package com.maggie.app.data.model

import kotlinx.serialization.Serializable

/**
 * What a meal could put on the grocery list, from `GET /api/meals/{id}/grocery_preview`
 * (MAG-295) — one line per product and recipe unit.
 */
@Serializable
data class MealGroceryPreview(
    val mealId: String,
    val groceryChoiceMadeAt: String? = null,
    val ingredients: List<MealGroceryIngredient> = emptyList(),
)

@Serializable
data class MealGroceryIngredient(
    val ingredientId: String,
    val name: String,
    // What the recipes ask for.
    val quantity: Float,
    val unit: CookbookUnit,
    val packaging: MealGroceryPackaging? = null,
    // What goes on the list: packagings when the product has one, the recipe quantity otherwise.
    val toBuy: MealGroceryToBuy,
    val stockState: ProductStockState = ProductStockState.IN_STOCK,
    // True for « Stock faible » and « Rupture »: what a client ticks by default.
    val suggested: Boolean = false,
    // What is already on the list for this product, the other meals behind it, and the total with this meal (MAG-380).
    val onList: MealGroceryOnList? = null,
    val otherMeals: List<MealGroceryOtherMeal> = emptyList(),
    val withThisMeal: MealGroceryWithThisMeal? = null,
)

@Serializable
data class MealGroceryOnList(
    val needed: List<Need> = emptyList(),
    val packages: Int? = null,
)

@Serializable
data class MealGroceryOtherMeal(
    val mealId: String,
    val name: String,
    // `YYYY-MM-DD`.
    val date: String,
    val quantity: Double,
    val unit: String? = null,
)

@Serializable
data class MealGroceryWithThisMeal(
    val needed: List<Need> = emptyList(),
    val packagesTotal: Int? = null,
    val packagesToAdd: Int? = null,
)

@Serializable
data class MealGroceryPackaging(
    val unit: CookbookUnit,
    val size: Float? = null,
    val sizeUnit: CookbookUnit? = null,
)

@Serializable
data class MealGroceryToBuy(
    val quantity: Float,
    val unit: CookbookUnit,
)

/** « 1 paquet (500 g) », « 2 bocaux », « 300 g »: what the line puts on the list, as the owner says it. */
fun MealGroceryIngredient.toBuyLabel(): String {
    val quantityText = formatQuantity(toBuy.quantity)
    val unitText = toBuy.unit.frenchLabel(toBuy.quantity)
    val content = packaging?.takeIf { it.unit == toBuy.unit }?.contentLabel()
    return listOfNotNull("$quantityText $unitText", content?.let { "($it)" }).joinToString(" ")
}

private fun MealGroceryPackaging.contentLabel(): String? {
    val size = size ?: return null
    val sizeUnit = sizeUnit ?: return null
    return "${formatQuantity(size)} ${sizeUnit.frenchLabel(size)}"
}

private val shortWeekdays = listOf("lun.", "mar.", "mer.", "jeu.", "ven.", "sam.", "dim.")

/** « jeu. » for `2026-10-15`; null when the date is not a `YYYY-MM-DD` one. */
internal fun shortFrenchWeekday(date: String): String? =
    runCatching { shortWeekdays[java.time.LocalDate.parse(date).dayOfWeek.value - 1] }.getOrNull()

/**
 * « déjà prévu : 300 g (Bolognaise, jeu.) »: what the other meals already put on the list for this
 * product (MAG-380). Null when nothing is planned yet.
 */
fun MealGroceryIngredient.alreadyPlannedLabel(): String? {
    val needs = onList?.needed?.takeIf { it.isNotEmpty() }
        ?: otherMeals.map { Need(it.quantity, it.unit) }
    val amount = needs.neededLabel() ?: return null
    val meals = otherMeals.joinToString("; ") { meal ->
        listOfNotNull(meal.name, shortFrenchWeekday(meal.date)).joinToString(", ")
    }
    return if (meals.isEmpty()) "déjà prévu : $amount" else "déjà prévu : $amount ($meals)"
}

/** « 2 paquets au total (+1) »: packagings once this meal is added; « (+N) » only when it adds some. */
fun MealGroceryIngredient.packagesTotalLabel(): String? {
    val total = withThisMeal?.packagesTotal ?: return null
    val unit = packaging?.unit ?: toBuy.unit
    val added = withThisMeal.packagesToAdd?.takeIf { it > 0 }
    return "$total ${unit.frenchLabel(total.toFloat())} au total" + (added?.let { " (+$it)" } ?: "")
}

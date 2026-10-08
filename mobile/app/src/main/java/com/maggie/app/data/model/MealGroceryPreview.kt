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

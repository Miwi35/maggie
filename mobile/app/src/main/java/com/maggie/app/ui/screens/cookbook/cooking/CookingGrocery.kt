package com.maggie.app.ui.screens.cookbook.cooking

import com.maggie.app.data.model.CookbookUnit
import com.maggie.app.data.model.GroceryItem
import com.maggie.app.data.model.Need
import com.maggie.app.data.model.Recipe
import com.maggie.app.data.model.Store
import com.maggie.app.data.model.label
import com.maggie.app.ui.screens.cookbook.grocery.AisleSection
import com.maggie.app.ui.screens.cookbook.grocery.StoreGroup
import com.maggie.app.ui.screens.cookbook.grocery.isPackaged
import com.maggie.app.ui.screens.cookbook.grocery.packSizeBase
import com.maggie.app.ui.screens.cookbook.grocery.storeGroupOf
import com.maggie.app.ui.screens.cookbook.grocery.toBase
import com.maggie.app.ui.screens.cookbook.recipes.ingredientId

/**
 * The shopping list seen from the Cuisine activity (MAG-583): the list's own stores and aisles, with what the
 * meal being cooked needs put first in each store.
 */

/** Where the list opens: a sheet over a phone held upright, a panel at the right of a tablet or a phone held sideways. */
enum class GroceryPresentation { SHEET, PANEL }

internal fun groceryPresentation(smallestWidthDp: Int, landscape: Boolean): GroceryPresentation =
    if (stepsLayout(smallestWidthDp, landscape) == StepsLayout.COLUMN) GroceryPresentation.PANEL else GroceryPresentation.SHEET

/** One line of the sheet: [needed] is the meal's need of the product (« 300 g »), null for an item off the meal. */
data class CookingGroceryLine(
    val item: GroceryItem,
    val needed: String? = null,
    /** « il en faut 300 g »: the list plans less than the meal needs, in the same unit. */
    val shortBy: String? = null,
)

/** A store: the meal's items first, then the others in the list's own aisle order. */
data class CookingGroceryStore(
    val store: Store?,
    val meal: List<CookingGroceryLine>,
    val others: List<AisleSection>,
    /** True when no item of [others] has an aisle: shown without aisle headers, as the list does. */
    val flat: Boolean,
)

/** What the recipes need of each product, by product id, in base units (g, ml, a piece…). */
internal fun mealNeeds(recipes: List<Recipe>): Map<String, List<Need>> {
    val sums = linkedMapOf<String, MutableMap<CookbookUnit, Double>>()
    for (recipe in recipes) {
        for (line in recipe.ingredients) {
            val productId = line.ingredientId().takeIf { it.isNotEmpty() } ?: continue
            val (quantity, unit) = toBase(line.quantity.toDouble(), line.unit)
            val byUnit = sums.getOrPut(productId) { linkedMapOf() }
            byUnit[unit] = (byUnit[unit] ?: 0.0) + quantity
        }
    }
    return sums.mapValues { (_, byUnit) -> byUnit.map { (unit, quantity) -> Need(roundMilli(quantity), unit.name.lowercase()) } }
}

/** What the line plans to buy, in base units; null when it says no quantity. */
private fun GroceryItem.planned(): Pair<Double, CookbookUnit>? {
    val quantity = quantity?.toDouble() ?: return null
    if (isPackaged) {
        val (size, unit) = product?.packSizeBase() ?: return null
        return quantity * size to unit
    }
    return toBase(quantity, unit ?: return null)
}

/** The need in the unit the line plans in, when the line plans less than it. */
private fun GroceryItem.shortfall(needs: List<Need>): Need? {
    val (planned, unit) = planned() ?: return null
    return needs.firstOrNull { it.unit == unit.name.lowercase() && planned + EPSILON < it.quantity }
}

// The API's quantities are floats: 0,2 kg is 200,000003 g.
private fun roundMilli(value: Double): Double = Math.round(value * 1000) / 1000.0

private const val EPSILON = 1e-3

/**
 * The list's stores, the items of the meal [needs] first in each one, marked; an item with no product, or a
 * product the recipes do not use, stays among the others. A store left with no item is dropped.
 */
internal fun cookingGroceryStores(groups: List<StoreGroup>, needs: Map<String, List<Need>>): List<CookingGroceryStore> =
    groups.mapNotNull { group ->
        if (group.items.isEmpty()) return@mapNotNull null
        val (forMeal, rest) = group.items.partition { it.product?.id in needs }
        val others = storeGroupOf(group.store, rest)
        CookingGroceryStore(
            store = group.store,
            meal = forMeal.map { item ->
                val need = needs.getValue(item.product!!.id)
                CookingGroceryLine(item, needed = need.joinToString(" + ") { it.label() }, shortBy = item.shortfall(need)?.label())
            },
            others = others.sections.ifEmpty { listOf(AisleSection(null, others.items)) }.filter { it.items.isNotEmpty() },
            flat = others.sections.isEmpty(),
        )
    }

/** The figure on the cart: what is still to buy today (the groups leave out what is put off to a later day). */
internal fun remainingCount(groups: List<StoreGroup>): Int = groups.sumOf { group -> group.items.count { !it.checked } }

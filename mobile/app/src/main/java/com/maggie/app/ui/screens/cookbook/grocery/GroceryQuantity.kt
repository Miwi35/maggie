package com.maggie.app.ui.screens.cookbook.grocery

import com.maggie.app.data.model.CookbookUnit
import com.maggie.app.data.model.GroceryItem
import com.maggie.app.data.model.formatQuantity as formatSize
import com.maggie.app.data.model.frenchLabel
import kotlin.math.roundToInt

// Step of the − / + buttons on a list line (MAG-291), the same as the admin's: a
// counted unit moves by one, a weight or a volume by what a shopper adds at a
// time. The lowest a line can be taken to is one step: − never removes the line.
fun quantityStep(unit: CookbookUnit?): Float = when (unit) {
    CookbookUnit.G, CookbookUnit.ML -> 100f
    CookbookUnit.CL -> 10f
    CookbookUnit.KG, CookbookUnit.L -> 0.1f
    else -> 1f
}

// 0.1f + 0.2f is not 0.3f: quantities are kept to three decimals.
private fun round(value: Float): Float = (value * 1000).roundToInt() / 1000f

fun increasedQuantity(quantity: Float?, unit: CookbookUnit?): Float =
    round((quantity ?: 0f) + quantityStep(unit))

fun decreasedQuantity(quantity: Float?, unit: CookbookUnit?): Float =
    maxOf(quantityStep(unit), round((quantity ?: 0f) - quantityStep(unit)))

fun canDecreaseQuantity(quantity: Float?, unit: CookbookUnit?): Boolean =
    quantity != null && quantity > quantityStep(unit)

/** `null` when the text is no quantity to send: empty, not a number, zero or negative. */
fun parseQuantity(text: String): Float? {
    val value = text.trim().replace(',', '.').toFloatOrNull() ?: return null
    return if (value.isFinite() && value > 0f) round(value) else null
}

fun formatQuantity(quantity: Float): String {
    val rounded = round(quantity)
    val text = if (rounded == rounded.toLong().toFloat()) rounded.toLong().toString() else rounded.toString()
    return text.replace('.', ',')
}

/** « 2 paquets », « 500 g »: the unit as the shopper says it, agreeing with the quantity. */
fun unitLabel(unit: CookbookUnit?, quantity: Float): String {
    val plural = quantity > 1f
    return when (unit) {
        null -> ""
        CookbookUnit.G -> "g"
        CookbookUnit.KG -> "kg"
        CookbookUnit.ML -> "ml"
        CookbookUnit.L -> "l"
        CookbookUnit.CL -> "cl"
        CookbookUnit.PIECE -> if (plural) "pièces" else "pièce"
        CookbookUnit.BUNCH -> if (plural) "bottes" else "botte"
        CookbookUnit.CAN -> if (plural) "boîtes" else "boîte"
        CookbookUnit.BOTTLE -> if (plural) "bouteilles" else "bouteille"
        CookbookUnit.PACK -> if (plural) "paquets" else "paquet"
        CookbookUnit.SACHET -> if (plural) "sachets" else "sachet"
        CookbookUnit.JAR -> if (plural) "bocaux" else "bocal"
    }
}

/**
 * The unit a line is counted in. A product with a packaging is counted in it: a
 * line with no unit of its own reads as that packaging, « Riz » alone being one
 * pack (MAG-299).
 */
val GroceryItem.countedUnit: CookbookUnit?
    get() = unit ?: product?.packagingUnit

/**
 * « (500 g) »: what one pack holds, shown after the quantity on a line counted in
 * the packaging itself. A line in grams of the same product says its weight, not its pack.
 */
fun GroceryItem.packagingContent(): String? {
    val product = product ?: return null
    val packaging = product.packagingUnit ?: return null
    val size = product.packagingSize ?: return null
    val sizeUnit = product.packagingSizeUnit ?: return null
    if (unit != null && unit != packaging) return null
    return "(${formatSize(size)} ${sizeUnit.frenchLabel(size)})"
}

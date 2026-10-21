package com.maggie.app.ui.screens.cookbook.grocery

import com.maggie.app.data.model.CookbookUnit
import com.maggie.app.data.model.GroceryItem
import com.maggie.app.data.model.formatQuantity as formatSize
import com.maggie.app.data.model.frenchLabel
import com.maggie.app.data.model.PurchasedItem
import com.maggie.app.data.model.neededLabel
import com.maggie.app.data.model.packagingLabel
import kotlin.math.roundToInt

// Every line has − and + (MAG-536). A packaged product is a whole number of packagings (MAG-299),
// any other line moves by a step that suits its unit; − never goes below one step —
// removing a line is the delete gesture.
fun increasedQuantity(quantity: Float?, step: Float = 1f): Float = (quantity ?: 0f) + step

fun decreasedQuantity(quantity: Float?, step: Float = 1f): Float = maxOf(step, (quantity ?: 0f) - step)

fun canDecreaseQuantity(quantity: Float?, step: Float = 1f): Boolean = quantity != null && quantity > step

/** How far − and + move a line counted in `unit`: 100 g, 0,5 kg, one piece. */
fun stepOf(unit: CookbookUnit?): Float = when (unit) {
    CookbookUnit.G, CookbookUnit.ML -> 100f
    CookbookUnit.CL -> 10f
    CookbookUnit.KG, CookbookUnit.L -> 0.5f
    else -> 1f
}

/**
 * `null` when the text is no quantity: empty, zero, negative or not a number. A count of
 * packagings is whole; a loose quantity may have decimals (« 1,5 »).
 */
fun parseQuantity(text: String, whole: Boolean = true): Float? {
    val trimmed = text.trim()
    if (whole) {
        val value = trimmed.toIntOrNull() ?: return null
        return if (value > 0) value.toFloat() else null
    }
    val value = trimmed.replace(',', '.').toFloatOrNull() ?: return null
    return if (value > 0f && value.isFinite()) value else null
}

fun formatQuantity(quantity: Float): String {
    val rounded = (quantity * 1000).roundToInt() / 1000f
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

/** A product with a packaging is counted in it, in whole packagings. */
val GroceryItem.isPackaged: Boolean
    get() = product?.packagingUnit != null

/** The unit a line is counted in: its product's packaging, else its own unit. */
val GroceryItem.countedUnit: CookbookUnit?
    get() = product?.packagingUnit ?: unit

/** How far − and + move this line. */
val GroceryItem.quantityStep: Float
    get() = if (isPackaged) 1f else stepOf(unit)

/** « (500 g) »: what one packaging holds, shown after the number of packagings. */
fun GroceryItem.packagingContent(): String? {
    val product = product ?: return null
    if (product.packagingUnit == null) return null
    val size = product.packagingSize ?: return null
    val sizeUnit = product.packagingSizeUnit ?: return null
    return "(${formatSize(size)} ${sizeUnit.frenchLabel(size)})"
}

/**
 * « 2 paquets · paquet de 500 g », « 3 pièces », « 800 g »: what a past errand's line bought
 * (MAG-488). A packaged line is counted in its packaging, as it was on the list; null with no quantity.
 */
fun PurchasedItem.quantityLabel(): String? {
    val quantity = quantity ?: return packagingLabel(packagingUnit, packagingSize, packagingSizeUnit)
    val counted = packagingUnit ?: unit
    val unitText = unitLabel(counted, quantity)
    val amount = formatQuantity(quantity) + if (unitText.isNotEmpty()) " $unitText" else ""
    // « 2 paquets · paquet » says nothing more: the packaging is only worth adding with its size.
    if (packagingUnit == null || packagingSize == null || packagingSizeUnit == null) return amount
    return "$amount · ${packagingLabel(packagingUnit, packagingSize, packagingSizeUnit)}"
}

/** « 800 g pour les repas »: the reminder under a line, never edited (MAG-380). */
fun GroceryItem.neededReminder(): String? {
    return needed.neededLabel()?.let { "$it pour les repas" }
}

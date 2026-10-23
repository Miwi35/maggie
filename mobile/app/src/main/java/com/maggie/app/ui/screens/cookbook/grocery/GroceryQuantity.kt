package com.maggie.app.ui.screens.cookbook.grocery

import com.maggie.app.data.model.CookbookUnit
import com.maggie.app.data.model.GroceryItem
import com.maggie.app.data.model.Need
import com.maggie.app.data.model.label
import com.maggie.app.data.model.formatQuantity as formatSize
import com.maggie.app.data.model.frenchLabel
import com.maggie.app.data.model.PurchasedItem
import com.maggie.app.data.model.neededLabel
import com.maggie.app.data.model.packagingLabel
import kotlin.math.roundToInt

// A packaged product's line is a whole number of packagings (MAG-299): − and +
// move it by one, and − never goes below one — removing a line is the delete gesture.
fun increasedQuantity(quantity: Float?): Float = (quantity ?: 0f) + 1f

fun decreasedQuantity(quantity: Float?): Float = maxOf(1f, (quantity ?: 0f) - 1f)

fun canDecreaseQuantity(quantity: Float?): Boolean = quantity != null && quantity > 1f

/** `null` when the text is no number of packagings: empty, not a whole number, zero or negative. */
fun parseQuantity(text: String): Float? {
    val value = text.trim().toIntOrNull() ?: return null
    return if (value > 0) value.toFloat() else null
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

/** A product with a packaging is counted in it; any other has no quantity to manage on its line. */
val GroceryItem.isPackaged: Boolean
    get() = product?.packagingUnit != null

/** The unit a packaged line is counted in: always its product's packaging. */
val GroceryItem.countedUnit: CookbookUnit?
    get() = product?.packagingUnit

/** « (500 g) »: what one packaging holds, shown after the number of packagings. */
fun GroceryItem.packagingContent(): String? {
    val product = product ?: return null
    if (product.packagingUnit == null) return null
    val size = product.packagingSize ?: return null
    val sizeUnit = product.packagingSizeUnit ?: return null
    return "(${formatSize(size)} ${sizeUnit.frenchLabel(size)})"
}

/**
 * « 800 g », « 300 g + 2 pièces »: what the meals need, shown on a line with nothing to adjust
 * (MAG-380). Falls back to the line's own quantity when the API sent no need.
 */
fun GroceryItem.quantityHint(): String? {
    needed.neededLabel()?.let { return it }
    val quantity = quantity ?: return null
    val unitText = unitLabel(unit, quantity)
    return formatQuantity(quantity) + if (unitText.isNotEmpty()) " $unitText" else ""
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

/** What was asked of a packaged line, in base units: « 1,4 kg » is 1400 g (MAG-573). Null when nothing was asked in a quantity. */
fun GroceryItem.desiredNeed(): Need? {
    val quantity = desiredQuantity ?: return null
    val unit = desiredUnit ?: return null
    return Need(quantity, unit.name.lowercase())
}

/**
 * The reminder under a packaged line, never edited (MAG-380, MAG-573). With a wanted quantity:
 * « 1,4 kg (dont 800 g pour les recettes) », the part in brackets being what the meals need of it;
 * a need above what is wanted reads « 300 g · 800 g pour les recettes ». Without one, « 800 g pour les repas ».
 */
fun GroceryItem.neededReminder(): String? {
    if (!isPackaged) return null
    val wanted = desiredNeed() ?: return needed.neededLabel()?.let { "$it pour les repas" }
    val need = needed?.firstOrNull { it.unit == wanted.unit } ?: return wanted.label()
    return if (need.quantity <= wanted.quantity + 1e-6) {
        "${wanted.label()} (dont ${need.label()} pour les recettes)"
    } else {
        "${wanted.label()} · ${need.label()} pour les recettes"
    }
}

/**
 * The line with [count] packagings, as the API leaves it (`DesiredQuantity::enforce`): + adds a packaging
 * without changing what is wanted; − below what the wanted quantity needs lowers it to what the packagings
 * hold. A wanted quantity that cannot be compared to the packagings is dropped.
 */
fun GroceryItem.withPackagings(count: Int): GroceryItem {
    val held = copy(quantity = count.toFloat())
    val wanted = desiredQuantity ?: return held
    val size = product?.packSizeBase()
    if (size == null || desiredUnit != size.second) {
        return held.copy(desiredQuantity = null, desiredUnit = null)
    }
    val content = count * size.first
    return if (wanted > content + 1e-6) held.copy(desiredQuantity = content) else held
}

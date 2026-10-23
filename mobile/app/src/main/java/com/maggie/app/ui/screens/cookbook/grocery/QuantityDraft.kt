package com.maggie.app.ui.screens.cookbook.grocery

import com.maggie.app.data.model.CookbookUnit
import com.maggie.app.data.model.Need
import com.maggie.app.data.model.Product
import com.maggie.app.data.model.formatQuantity as formatSize
import com.maggie.app.data.model.frenchLabel
import com.maggie.app.data.model.label
import com.maggie.app.data.model.toCookbookUnit
import kotlin.math.ceil
import kotlin.math.max

// What the add and edit sheets let one type for a line (MAG-573): a quantity with its unit, or —
// for a product bought in a packaging — a number of packagings. Exactly one of the two is sent.
enum class QuantityMode { QUANTITY, PACKAGINGS }

/** Where the number of packagings of a draft comes from, as long as the owner has not typed it. */
enum class Prefill {
    /** Typed, or nothing to prefill from. */
    NONE,

    /** One packaging, for want of anything better: the week's need replaces it when it arrives. */
    DEFAULT,

    /** The product's automatic restock. */
    RESTOCK,

    /** The week's recipe need, rounded up to whole packagings. */
    NEED,
}

data class QuantityDraft(
    val mode: QuantityMode = QuantityMode.QUANTITY,
    val quantity: String = "",
    val unit: CookbookUnit? = null,
    val packagings: String = "",
    val prefill: Prefill = Prefill.NONE,
) {
    /** The number of packagings comes from the product's restock or from the week's recipes: flagged on the sheet. */
    val prefilled: Boolean get() = prefill == Prefill.RESTOCK || prefill == Prefill.NEED
}

/** What goes to the API: [packagings] alone, or [quantity] with its [unit]. */
data class QuantityPayload(val quantity: Float? = null, val unit: String? = null, val packagings: Int? = null)

private fun Product?.isPackaged(): Boolean = this?.packagingUnit != null

// The unit a quantity is typed in when the owner leaves « Conditionnement »: the product's usual
// one, else the unit its packaging is measured in (g for 500 g).
private fun Product?.typedUnit(): CookbookUnit? = this?.defaultUnit ?: this?.packagingSizeUnit

private fun CookbookUnit.apiName(): String = name.lowercase()

private val BASE_FACTORS = mapOf(
    CookbookUnit.KG to (CookbookUnit.G to 1000.0),
    CookbookUnit.L to (CookbookUnit.ML to 1000.0),
    CookbookUnit.CL to (CookbookUnit.ML to 10.0),
)

/** The quantity in the base unit of its family (g, ml), as the API keeps what was asked. */
private fun toBase(quantity: Double, unit: CookbookUnit): Pair<Double, CookbookUnit> =
    BASE_FACTORS[unit]?.let { (base, factor) -> quantity * factor to base } ?: (quantity to unit)

/** What one packaging holds, in base units: 500 g for a 500 g pack; null when the product does not say. */
internal fun Product.packSizeBase(): Pair<Double, CookbookUnit>? {
    val size = packagingSize ?: return null
    val sizeUnit = packagingSizeUnit ?: return null
    return toBase(size.toDouble(), sizeUnit)
}

/**
 * Opening the add sheet on a product: its restock in packagings, else the week's recipe [need]
 * rounded up to packagings, else one packaging, else a quantity in its usual unit.
 */
fun initialQuantityDraft(product: Product?, need: Need? = null): QuantityDraft {
    if (!product.isPackaged()) return QuantityDraft(unit = product?.defaultUnit)
    val restock = product?.restockQuantity?.takeIf { it >= 1 }
    val needed = packagingsForNeed(product!!, need)
    val (count, prefill) = when {
        restock != null -> restock to Prefill.RESTOCK
        needed != null -> needed to Prefill.NEED
        else -> 1 to Prefill.DEFAULT
    }
    return QuantityDraft(
        mode = QuantityMode.PACKAGINGS,
        unit = product.typedUnit(),
        packagings = count.toString(),
        prefill = prefill,
    )
}

/**
 * The week's need reached the sheet after the product was chosen: a draft that still holds the
 * one-packaging default takes it; one prefilled from the restock, or typed on, is left alone.
 */
fun QuantityDraft.withRecipeNeed(product: Product?, need: Need?): QuantityDraft {
    if (prefill != Prefill.DEFAULT || mode != QuantityMode.PACKAGINGS || product == null) return this
    val needed = packagingsForNeed(product, need) ?: return this
    return copy(packagings = needed.toString(), prefill = Prefill.NEED)
}

/** Editing a line: the packagings it holds now when its product is packaged, an empty quantity otherwise. */
fun quantityDraftForLine(quantity: Float?, product: Product?): QuantityDraft =
    if (product.isPackaged()) {
        QuantityDraft(
            mode = QuantityMode.PACKAGINGS,
            unit = product.typedUnit(),
            packagings = (quantity?.takeIf { it >= 1f }?.let { Math.round(it) } ?: 1).toString(),
        )
    } else {
        QuantityDraft()
    }

/** `null` when the text is no number of packagings: empty, not a whole number, zero or negative. */
fun parsePackagings(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it >= 1 }

private fun parseAmount(text: String): Float? =
    text.trim().replace(',', '.').toFloatOrNull()?.takeIf { it.isFinite() && it > 0f }

/** « Ajouter » takes no quantity at all, but not a number of packagings below one nor a quantity that is no positive number. */
fun QuantityDraft.isValid(): Boolean = when (mode) {
    QuantityMode.PACKAGINGS -> parsePackagings(packagings) != null
    QuantityMode.QUANTITY -> quantity.isBlank() || parseAmount(quantity) != null
}

fun QuantityDraft.payload(): QuantityPayload = when (mode) {
    QuantityMode.PACKAGINGS -> QuantityPayload(packagings = parsePackagings(packagings))
    QuantityMode.QUANTITY -> QuantityPayload(quantity = parseAmount(quantity), unit = unit?.apiName())
}

private val FAMILIES = mapOf(
    CookbookUnit.G to ("mass" to 1f),
    CookbookUnit.KG to ("mass" to 1000f),
    CookbookUnit.ML to ("volume" to 1f),
    CookbookUnit.CL to ("volume" to 10f),
    CookbookUnit.L to ("volume" to 1000f),
)

private const val EPSILON = 1e-4f

/** How many packagings a quantity is, fractions included; null when its unit cannot be turned into the packaging. */
private fun fractionOfPack(product: Product, quantity: Float, unit: CookbookUnit?): Float? {
    val packagingUnit = product.packagingUnit ?: return null
    val typed = unit ?: packagingUnit
    val size = product.packagingSize
    val sizeUnit = product.packagingSizeUnit
    return when {
        typed == packagingUnit -> quantity
        size == null || sizeUnit == null -> null
        typed == sizeUnit -> quantity / size
        else -> {
            val from = FAMILIES[typed]
            val to = FAMILIES[sizeUnit]
            if (from != null && to != null && from.first == to.first) quantity * from.second / (size * to.second) else null
        }
    }
}

/**
 * The API's `Packaging::inPackagings` rule: a whole number of packagings, rounded up, at least one,
 * whatever the unit the quantity is typed in. A unit that cannot be turned into the packaging counts as one.
 */
fun packagingsFor(product: Product, quantity: Float, unit: CookbookUnit?): Int {
    if (product.packagingUnit == null) return 1
    val fraction = fractionOfPack(product, quantity, unit) ?: return 1
    return max(1, ceil(fraction - EPSILON).toInt())
}

/**
 * The packagings that cover the week's recipe [need]: `ceil(need / size)`. Null without a need, without
 * a packaging, or when the need's unit cannot be compared with what a packaging holds.
 */
fun packagingsForNeed(product: Product, need: Need?): Int? {
    if (need == null || product.packagingUnit == null) return null
    val unit = need.unit?.toCookbookUnit() ?: return null
    val fraction = fractionOfPack(product, need.quantity.toFloat(), unit) ?: return null
    return max(1, ceil(fraction - EPSILON).toInt())
}

private fun Product.content(): String {
    val size = packagingSize ?: return ""
    val sizeUnit = packagingSizeUnit ?: return ""
    return " de ${formatSize(size)} ${sizeUnit.frenchLabel(size)}"
}

/** « paquets de 500 g »: beside the stepper, what each of its units is. */
fun packagingCaption(product: Product, count: Int): String =
    (product.packagingUnit?.frenchLabel(count.toFloat()) ?: "") + product.content()

/** « 2 paquets », « 1 paquet »: a count of the product's packagings. */
fun packagingsText(product: Product, count: Int): String =
    "$count ${product.packagingUnit?.frenchLabel(count.toFloat()) ?: ""}".trim()

/**
 * What a line added with this draft holds, in packagings: the count typed, a quantity turned into
 * packagings, or — with nothing typed — what the API fills in: the restock, else the week's need, else one.
 * Null when the draft is invalid or the product has no packaging.
 */
fun QuantityDraft.packagingsToBuy(product: Product?, need: Need?): Int? {
    if (product?.packagingUnit == null) return null
    return when (mode) {
        QuantityMode.PACKAGINGS -> parsePackagings(packagings)
        QuantityMode.QUANTITY ->
            if (quantity.isBlank()) {
                product.restockQuantity?.takeIf { it >= 1 } ?: packagingsForNeed(product, need) ?: 1
            } else {
                parseAmount(quantity)?.let { packagingsFor(product, it, unit) }
            }
    }
}

/** What is wanted, in base units: the packagings' content, or the quantity typed when it is in the packaging's family. */
private fun QuantityDraft.wanted(product: Product, packs: Int): Pair<Double, CookbookUnit>? {
    val size = product.packSizeBase()
    val amount = if (mode == QuantityMode.QUANTITY) parseAmount(quantity) else null
    val typedUnit = unit
    if (amount == null || typedUnit == null || typedUnit == product.packagingUnit) {
        return size?.let { (value, baseUnit) -> packs * value to baseUnit }
    }
    val base = toBase(amount.toDouble(), typedUnit)
    return base.takeIf { size != null && size.second == it.second }
}

/**
 * « 1 kg (dont 800 g pour les recettes) · 2 paquets »: what is wanted, how much of it the week's
 * [need] is, and the packagings that makes. The part in brackets shows when the need fits in what is wanted.
 */
fun QuantityDraft.summary(product: Product?, need: Need?): String? {
    if (product?.packagingUnit == null) return null
    val packs = packagingsToBuy(product, need) ?: return null
    val packsText = packagingsText(product, packs)
    val (value, baseUnit) = wanted(product, packs) ?: return packsText
    val unitName = baseUnit.apiName()
    var text = Need(value, unitName).label()
    if (need != null && need.unit == unitName && need.quantity <= value + 1e-6) {
        text += " (dont ${need.label()} pour les recettes)"
    }
    return "$text · $packsText"
}

/** The packagings that would cover the week's [need] when this draft falls short of it; null when it covers it, or nothing is known. */
fun QuantityDraft.packagingsToCover(product: Product?, need: Need?): Int? {
    if (product == null) return null
    val needed = packagingsForNeed(product, need) ?: return null
    val packs = packagingsToBuy(product, need) ?: return null
    return needed.takeIf { packs < it }
}

/** « Ne couvre pas les besoins de la semaine (1,2 kg pour les recettes) »; null when the draft covers them. */
fun QuantityDraft.coverageAlert(product: Product?, need: Need?): String? {
    if (need == null || packagingsToCover(product, need) == null) return null
    return "Ne couvre pas les besoins de la semaine (${need.label()} pour les recettes)"
}

/** « Ajuster à 3 paquets »: the button of the alert; null when there is nothing to adjust. */
fun QuantityDraft.adjustLabel(product: Product?, need: Need?): String? {
    val count = packagingsToCover(product, need) ?: return null
    return "Ajuster à ${packagingsText(product!!, count)}"
}

/** The draft in packagings, at the count that covers the week's need. */
fun QuantityDraft.adjustedToNeed(product: Product?, need: Need?): QuantityDraft {
    val count = packagingsToCover(product, need) ?: return this
    return copy(mode = QuantityMode.PACKAGINGS, packagings = count.toString(), prefill = Prefill.NONE)
}

/**
 * Quantities offered under the « Quantité » field: one, two and three packagings of the product and the week's
 * [need] (when it is in the packaging's unit family), smallest first. Empty when the product does not say what a packaging holds.
 */
fun suggestedQuantities(product: Product?, need: Need?): List<Need> {
    val (size, unit) = product?.packSizeBase() ?: return emptyList()
    val unitName = unit.apiName()
    val multiples = (1..3).map { Need(size * it, unitName) }
    val fromNeed = need?.takeIf { it.unit == unitName }?.let { listOf(it) }.orEmpty()
    return (multiples + fromNeed).distinctBy { it.quantity }.sortedBy { it.quantity }
}

/** A suggested quantity typed into the « Quantité » field, in its own unit. */
fun QuantityDraft.withSuggestedQuantity(suggestion: Need): QuantityDraft = copy(
    mode = QuantityMode.QUANTITY,
    quantity = formatSize(suggestion.quantity.toFloat()),
    unit = suggestion.unit?.toCookbookUnit() ?: unit,
    prefill = Prefill.NONE,
)

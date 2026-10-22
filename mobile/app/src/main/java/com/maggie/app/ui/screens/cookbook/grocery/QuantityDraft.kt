package com.maggie.app.ui.screens.cookbook.grocery

import com.maggie.app.data.model.CookbookUnit
import com.maggie.app.data.model.Product
import com.maggie.app.data.model.formatQuantity as formatSize
import com.maggie.app.data.model.frenchLabel
import kotlin.math.ceil
import kotlin.math.max

// What the add and edit sheets let one type for a line (MAG-573): a quantity with its unit, or —
// for a product bought in a packaging — a number of packagings. Exactly one of the two is sent.
enum class QuantityMode { QUANTITY, PACKAGINGS }

data class QuantityDraft(
    val mode: QuantityMode = QuantityMode.QUANTITY,
    val quantity: String = "",
    val unit: CookbookUnit? = null,
    val packagings: String = "",
    /** The number of packagings comes from the product's automatic restock. */
    val prefilled: Boolean = false,
)

/** What goes to the API: [packagings] alone, or [quantity] with its [unit]. */
data class QuantityPayload(val quantity: Float? = null, val unit: String? = null, val packagings: Int? = null)

private fun Product?.isPackaged(): Boolean = this?.packagingUnit != null

// The unit a quantity is typed in when the owner leaves « Conditionnement »: the product's usual
// one, else the unit its packaging is measured in (g for 500 g).
private fun Product?.typedUnit(): CookbookUnit? = this?.defaultUnit ?: this?.packagingSizeUnit

/** Opening the add sheet on a product: its restock in packagings, else one packaging, else a quantity in its usual unit. */
fun initialQuantityDraft(product: Product?): QuantityDraft {
    if (!product.isPackaged()) return QuantityDraft(unit = product?.defaultUnit)
    val restock = product?.restockQuantity?.takeIf { it >= 1 }
    return QuantityDraft(
        mode = QuantityMode.PACKAGINGS,
        unit = product.typedUnit(),
        packagings = (restock ?: 1).toString(),
        prefilled = restock != null,
    )
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
    QuantityMode.QUANTITY -> QuantityPayload(quantity = parseAmount(quantity), unit = unit?.name?.lowercase())
}

private val FAMILIES = mapOf(
    CookbookUnit.G to ("mass" to 1f),
    CookbookUnit.KG to ("mass" to 1000f),
    CookbookUnit.ML to ("volume" to 1f),
    CookbookUnit.CL to ("volume" to 10f),
    CookbookUnit.L to ("volume" to 1000f),
)

private const val EPSILON = 1e-4f

/**
 * The API's `Packaging::inPackagings` rule: a whole number of packagings, rounded up, at least one,
 * whatever the unit the quantity is typed in. A unit that cannot be turned into the packaging counts as one.
 */
fun packagingsFor(product: Product, quantity: Float, unit: CookbookUnit?): Int {
    val packagingUnit = product.packagingUnit ?: return 1
    val typed = unit ?: packagingUnit
    val size = product.packagingSize
    val sizeUnit = product.packagingSizeUnit
    val fraction: Float? = when {
        typed == packagingUnit -> quantity
        size == null || sizeUnit == null -> null
        typed == sizeUnit -> quantity / size
        else -> {
            val from = FAMILIES[typed]
            val to = FAMILIES[sizeUnit]
            if (from != null && to != null && from.first == to.first) quantity * from.second / (size * to.second) else null
        }
    }
    return if (fraction == null) 1 else max(1, ceil(fraction - EPSILON).toInt())
}

private fun Product.content(): String {
    val size = packagingSize ?: return ""
    val sizeUnit = packagingSizeUnit ?: return ""
    return " de ${formatSize(size)} ${sizeUnit.frenchLabel(size)}"
}

/** « paquets de 500 g »: beside the stepper, what each of its units is. */
fun packagingCaption(product: Product, count: Int): String =
    (product.packagingUnit?.frenchLabel(count.toFloat()) ?: "") + product.content()

private fun readable(quantity: Float, unit: CookbookUnit): String = when {
    unit == CookbookUnit.G && quantity >= 1000f -> "${formatSize(quantity / 1000f)} kg"
    unit == CookbookUnit.ML && quantity >= 1000f -> "${formatSize(quantity / 1000f)} l"
    else -> "${formatSize(quantity)} ${unit.frenchLabel(quantity)}"
}

/**
 * The conversion under the field: « = 1 kg » for 2 packs of 500 g, « = 1 paquet de 500 g à acheter »
 * for 300 g. Null when there is nothing to convert.
 */
fun QuantityDraft.conversionHint(product: Product?): String? {
    val packagingUnit = product?.packagingUnit ?: return null
    return when (mode) {
        QuantityMode.PACKAGINGS -> {
            val count = parsePackagings(packagings) ?: return null
            val size = product.packagingSize ?: return null
            val sizeUnit = product.packagingSizeUnit ?: return null
            "= ${readable(count * size, sizeUnit)}"
        }
        QuantityMode.QUANTITY -> {
            val amount = parseAmount(quantity) ?: return null
            val count = packagingsFor(product, amount, unit)
            "= $count ${packagingUnit.frenchLabel(count.toFloat())}${product.content()} à acheter"
        }
    }
}

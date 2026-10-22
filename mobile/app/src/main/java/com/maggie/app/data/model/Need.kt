package com.maggie.app.data.model

import kotlinx.serialization.Serializable

/**
 * What the meals need of a product, in base units (MAG-380): grams, millilitres or the
 * product's own unit (« piece »). The unit stays a string: a unit this app does not
 * know is shown as the API wrote it rather than failing the whole list.
 */
@Serializable
data class Need(
    val quantity: Double,
    val unit: String? = null,
)

/** The unit as the app knows it; null for a unit it does not. */
fun String.toCookbookUnit(): CookbookUnit? = when (this) {
    "g" -> CookbookUnit.G
    "kg" -> CookbookUnit.KG
    "ml" -> CookbookUnit.ML
    "l" -> CookbookUnit.L
    "cl" -> CookbookUnit.CL
    "piece" -> CookbookUnit.PIECE
    "bunch" -> CookbookUnit.BUNCH
    "can" -> CookbookUnit.CAN
    "bottle" -> CookbookUnit.BOTTLE
    "pack" -> CookbookUnit.PACK
    "sachet" -> CookbookUnit.SACHET
    "jar" -> CookbookUnit.JAR
    else -> null
}

/** « 800 g », « 1,5 kg » (from 1000 g), « 2 pièces »: one need as the owner reads it. */
fun Need.label(): String {
    var value = quantity
    var unitName = unit
    if (value >= 1000.0 && unitName == "g") {
        value /= 1000.0
        unitName = "kg"
    } else if (value >= 1000.0 && unitName == "ml") {
        value /= 1000.0
        unitName = "l"
    }
    val amount = formatQuantity(value.toFloat())
    val known = unitName?.toCookbookUnit()
    val unitText = when {
        unitName == null -> ""
        known != null -> known.frenchLabel(value.toFloat())
        else -> unitName
    }
    return if (unitText.isEmpty()) amount else "$amount $unitText"
}

/** « 800 g », « 300 g + 2 pièces »; null when there is nothing needed. */
fun List<Need>?.neededLabel(): String? =
    this?.takeIf { it.isNotEmpty() }?.joinToString(" + ") { it.label() }

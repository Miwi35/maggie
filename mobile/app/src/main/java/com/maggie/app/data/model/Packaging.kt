package com.maggie.app.data.model

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/** The French name of a unit, as the admin writes it: « paquet », « bocal ». */
val CookbookUnit.frenchName: String
    get() = when (this) {
        CookbookUnit.G -> "g"
        CookbookUnit.KG -> "kg"
        CookbookUnit.ML -> "ml"
        CookbookUnit.L -> "l"
        CookbookUnit.CL -> "cl"
        CookbookUnit.PIECE -> "pièce"
        CookbookUnit.BUNCH -> "botte"
        CookbookUnit.CAN -> "boîte"
        CookbookUnit.BOTTLE -> "bouteille"
        CookbookUnit.PACK -> "paquet"
        CookbookUnit.SACHET -> "sachet"
        CookbookUnit.JAR -> "bocal"
    }

private val CookbookUnit.frenchPlural: String
    get() = when (this) {
        CookbookUnit.PIECE -> "pièces"
        CookbookUnit.BUNCH -> "bottes"
        CookbookUnit.CAN -> "boîtes"
        CookbookUnit.BOTTLE -> "bouteilles"
        CookbookUnit.PACK -> "paquets"
        CookbookUnit.SACHET -> "sachets"
        CookbookUnit.JAR -> "bocaux"
        else -> frenchName
    }

private val SIZE_FORMAT = DecimalFormat("0.###", DecimalFormatSymbols(Locale.FRANCE))

/** A quantity as the owner writes it: « 500 », « 1,5 ». */
fun formatQuantity(value: Float): String = SIZE_FORMAT.format(value)

/** The unit for [count] of it: « paquet » for one, « paquets » for several. */
fun CookbookUnit.frenchLabel(count: Float): String = if (count > 1f) frenchPlural else frenchName

/**
 * What the product is bought in, as the owner says it: « paquet de 500 g »,
 * « bocal ». Null when the product has no packaging.
 */
fun Product.packagingLabel(): String? {
    val unit = packagingUnit ?: return null
    val size = packagingSize
    val sizeUnit = packagingSizeUnit
    if (size == null || sizeUnit == null) return unit.frenchName
    return "${unit.frenchName} de ${formatQuantity(size)} ${sizeUnit.frenchLabel(size)}"
}

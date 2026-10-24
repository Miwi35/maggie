package com.maggie.app.ui.screens.grocery

import com.maggie.app.data.model.Aisle
import com.maggie.app.data.model.Product

const val NO_AISLE_TITLE = "Sans rayon"

/** The products of one aisle of the chosen store; [aisleId] is null for « Sans rayon ». */
data class ProductSection(val aisleId: String?, val title: String, val products: List<Product>)

/**
 * The products cut by their aisle in [storeId], in the order of the way through it; « Sans rayon » last.
 * A product keeps the place the API gave it inside its section, and an empty aisle shows no header.
 */
fun groupByAisle(products: List<Product>, storeId: String, aisles: List<Aisle>): List<ProductSection> {
    val ordered = aisles.sortedBy { it.position }
    val known = ordered.map { it.id }.toSet()
    val byAisle = products.groupBy { product ->
        product.aisles.firstOrNull { it.store == storeId }?.aisle?.takeIf { it in known }
    }
    val sections = ordered.mapNotNull { aisle ->
        byAisle[aisle.id]?.let { ProductSection(aisle.id, aisle.name, it) }
    }
    return sections + listOfNotNull(byAisle[null]?.let { ProductSection(null, NO_AISLE_TITLE, it) })
}

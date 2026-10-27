package com.maggie.app.ui.screens.grocery

import com.maggie.app.data.model.Aisle
import com.maggie.app.data.model.Product
import com.maggie.app.data.model.ProductCategory

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

/**
 * The products cut by category, the way the Produits mockup groups them: the categories in their
 * usual order, « Autre » last, an empty one showing no header. A product keeps the place the API gave it.
 */
fun groupByCategory(products: List<Product>): List<ProductSection> {
    val byCategory = products.groupBy { it.category }
    val ordered = ProductCategory.entries.filter { it != ProductCategory.OTHER } + ProductCategory.OTHER
    return ordered.mapNotNull { category ->
        byCategory[category]?.let { ProductSection(null, category.label(), it) }
    }
}

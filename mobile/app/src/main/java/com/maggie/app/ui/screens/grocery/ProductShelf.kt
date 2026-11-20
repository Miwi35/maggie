package com.maggie.app.ui.screens.grocery

import com.maggie.app.data.model.Aisle
import com.maggie.app.data.model.Product
import com.maggie.app.data.model.Store

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

private const val STORE_IRI_PREFIX = "/api/stores/"

/**
 * Where to buy a product, as the Produits mockup words it: « Super U · Légumes — à défaut Biocoop · Fruits et légumes ».
 * A store with no aisle is named alone; a store the list does not know is left out; null when nothing is left.
 */
fun productWhere(product: Product, stores: List<Store>, aislesByStore: Map<String, List<Aisle>>): String? {
    fun part(iri: String?): String? {
        val storeId = iri?.removePrefix(STORE_IRI_PREFIX) ?: return null
        val store = stores.firstOrNull { it.id == storeId } ?: return null
        val aisleId = product.aisles.firstOrNull { it.store == storeId }?.aisle
        val aisle = aisleId?.let { id -> aislesByStore[storeId].orEmpty().firstOrNull { it.id == id } }
        return listOfNotNull(store.name, aisle?.name).joinToString(" · ")
    }
    val usual = part(product.preferredStore)
    val fallback = part(product.fallbackStore)?.let { "à défaut $it" }
    return listOfNotNull(usual, fallback).joinToString(" — ").ifEmpty { null }
}

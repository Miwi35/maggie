package com.maggie.app.ui.screens.cookbook.grocery

import com.maggie.app.data.model.Product
import com.maggie.app.data.model.Store

internal fun filterProducts(query: String, products: List<Product>): List<Product> {
    if (query.length < 2) return emptyList()
    return products.filter { it.name.contains(query, ignoreCase = true) }.take(10)
}

internal fun filterStores(query: String, stores: List<Store>): List<Store> {
    if (query.isBlank()) return stores
    return stores.filter { it.name.contains(query, ignoreCase = true) }
}

internal fun shouldShowCreateStore(query: String, stores: List<Store>): Boolean {
    val trimmed = query.trim()
    return trimmed.isNotEmpty() && stores.none { it.name.equals(trimmed, ignoreCase = true) }
}

internal fun resolvePreferredStore(product: Product, stores: List<Store>): Store? {
    val iri = product.preferredStore ?: return null
    val id = iri.substringAfterLast("/")
    return stores.find { it.id == id }
}

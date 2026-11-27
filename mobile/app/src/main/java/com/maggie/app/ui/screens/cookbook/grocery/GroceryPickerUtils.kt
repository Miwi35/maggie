package com.maggie.app.ui.screens.cookbook.grocery

import com.maggie.app.data.model.Aisle
import com.maggie.app.data.model.Product
import com.maggie.app.data.model.ProductCategory
import com.maggie.app.data.model.Store
import com.maggie.app.ui.screens.grocery.label
import java.text.Normalizer

internal fun filterProducts(query: String, products: List<Product>): List<Product> {
    if (query.length < 2) return emptyList()
    val key = searchKey(query)
    return products.filter { searchKey(it.name).contains(key) }.take(10)
}

/** Lower case, no accents, trailing -s/-x of each longer word dropped: « Poireaux » and « poireau » share a key. */
internal fun searchKey(text: String): String {
    val folded = Normalizer.normalize(text.trim().lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .replace("œ", "oe")
        .replace("æ", "ae")
    return folded.split(Regex("\\s+")).joinToString(" ") { if (it.length > 3) it.trimEnd('s', 'x') else it }
}

/** The product category a Ciqual food group most likely belongs to; the owner can change it afterwards. */
internal fun categoryForCiqualGroup(group: String?): ProductCategory {
    val g = searchKey(group ?: "")
    return when {
        "fruit" in g || "legume" in g -> ProductCategory.PRODUCE
        "viande" in g || "charcuterie" in g || "oeuf" in g -> ProductCategory.MEAT
        "poisson" in g -> ProductCategory.FISH
        "lait" in g || "fromage" in g || "laitier" in g -> ProductCategory.DAIRY
        "cereale" in g || "pain" in g || "pate" in g -> ProductCategory.GRAIN
        "boisson" in g -> ProductCategory.BEVERAGE
        "epice" in g || "herbe" in g -> ProductCategory.SPICE
        else -> ProductCategory.OTHER
    }
}

internal fun filterStores(query: String, stores: List<Store>): List<Store> {
    if (query.isBlank()) return stores
    return stores.filter { it.name.contains(query, ignoreCase = true) }
}

internal fun shouldShowCreateStore(query: String, stores: List<Store>): Boolean {
    val trimmed = query.trim()
    return trimmed.isNotEmpty() && stores.none { it.name.equals(trimmed, ignoreCase = true) }
}

internal fun filterAisles(query: String, aisles: List<Aisle>): List<Aisle> {
    if (query.isBlank()) return aisles
    return aisles.filter { it.name.contains(query.trim(), ignoreCase = true) }
}

/** An aisle's name is unique in its store whatever the case, as the API enforces. */
internal fun shouldShowCreateAisle(query: String, aisles: List<Aisle>): Boolean {
    val trimmed = query.trim()
    return trimmed.isNotEmpty() && aisles.none { it.name.equals(trimmed, ignoreCase = true) }
}

internal fun resolvePreferredStore(product: Product, stores: List<Store>): Store? {
    val iri = product.preferredStore ?: return null
    val id = iri.substringAfterLast("/")
    return stores.find { it.id == id }
}

/**
 * The line under the product's name on the add sheet: « Biocoop · Épicerie · se vend en paquet de 500 g ».
 * Each piece only when known (the aisle needs its store's aisles); null when none is.
 */
internal fun productLine(product: Product, stores: List<Store>, aislesByStore: Map<String, List<Aisle>>): String? {
    val store = resolvePreferredStore(product, stores)
    val aisle = store?.let { s ->
        val id = product.aisles.firstOrNull { it.store == s.id }?.aisle
        aislesByStore[s.id].orEmpty().firstOrNull { it.id == id }?.name
    }
    val soldAs = product.packagingUnit?.let { "se vend en ${packagingCaption(product, 1)}" }
    return listOfNotNull(store?.name, aisle, soldAs).joinToString(" · ").ifEmpty { null }
}

/** The search row's line under a product: « Produit · Biocoop · Crèmerie », each piece only when known. */
internal fun productSubtitle(product: Product, stores: List<Store>): String =
    listOfNotNull("Produit", resolvePreferredStore(product, stores)?.name, product.category.label()).joinToString(" \u00b7 ")

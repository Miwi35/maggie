package com.maggie.app.data.model

import kotlinx.serialization.Serializable

/**
 * An aisle of a store, in the order of the way through it (MAG-493): `position`
 * 1 is the first one walked past. `store` is the store's IRI (`/api/stores/{id}`).
 */
@Serializable
data class Aisle(
    val id: String,
    val store: String,
    val name: String,
    val position: Int = 0,
)

/** The aisle a grocery line carries: its product's aisle in its store. */
@Serializable
data class AisleSummary(
    val id: String,
    val name: String,
    val position: Int = 0,
)

/**
 * A product's aisle in one store, both as bare ids. On a PATCH, `aisle` may also be
 * the name of an aisle to create (last in that store).
 */
@Serializable
data class ProductAisle(
    val store: String,
    val aisle: String? = null,
)

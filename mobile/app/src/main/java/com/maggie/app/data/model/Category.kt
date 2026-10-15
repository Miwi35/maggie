package com.maggie.app.data.model

import kotlinx.serialization.Serializable

@Serializable
data class Category(
    val id: String,
    val name: String,
    // Deprecated by the API (a category has no nature): read only to know whether a rente
    // can be flagged, never shown. It is "income" on a category that already took the flag.
    val obligation: String? = null,
    // A rente: income that comes in without being worked for. Only ever true
    // on an income category, and it is what the independence counter counts.
    // Spelled as the API spells it — `passiveIncome`, not `isPassiveIncome`.
    val passiveIncome: Boolean = false,
    // A full IRI ("/api/categories/01H…"), not an id: what the provider
    // returns is the identifier, never something to prefix. CategoryCreateRequest
    // already sends it back under this name.
    val parent: String? = null,
    val color: String? = null,
    val icon: String? = null,
    // Level in the tree (1 to 3) and « Loisirs › Bars › Obsydian », both read-only
    // and computed by the API; the safe defaults are a main category named alone.
    val depth: Int = 1,
    val path: String? = null,
)

/** What the screens write for a category: its full path when the API gave one, its name otherwise. */
fun Category.displayPath(): String = path?.takeIf { it.isNotBlank() } ?: name

/** What a category line says under its name: « Rente » for a rente, nothing otherwise. */
fun categoryKindLabel(category: Category): String? = if (category.passiveIncome) "Rente" else null

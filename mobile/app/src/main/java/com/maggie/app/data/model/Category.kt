package com.maggie.app.data.model

import kotlinx.serialization.Serializable

@Serializable
data class Category(
    val id: String,
    val name: String,
    val obligation: String = "optional",
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

/** Human-readable French label for an obligation flag code. */
fun obligationLabel(obligation: String): String = when (obligation) {
    "mandatory" -> "Obligatoire"
    "optional" -> "Non-obligatoire"
    "saving" -> "Épargne"
    "investment" -> "Investissement"
    "debt" -> "Remboursement de prêt"
    "income" -> "Recette"
    else -> obligation
}

/** What kind of line a category is, rente included. */
fun categoryKindLabel(category: Category): String =
    if (category.passiveIncome) {
        "${obligationLabel(category.obligation)} · rente"
    } else {
        obligationLabel(category.obligation)
    }

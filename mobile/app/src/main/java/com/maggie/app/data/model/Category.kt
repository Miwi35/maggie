package com.maggie.app.data.model

import kotlinx.serialization.Serializable

@Serializable
data class Category(
    val id: String,
    val name: String,
    val obligation: String = "optional",
    // A full IRI ("/api/categories/01H…"), not an id: what the provider
    // returns is the identifier, never something to prefix. CategoryCreateRequest
    // already sends it back under this name.
    val parent: String? = null,
    val color: String? = null,
    val icon: String? = null,
)

/** Human-readable French label for an obligation flag code. */
fun obligationLabel(obligation: String): String = when (obligation) {
    "mandatory" -> "Obligatoire"
    "optional" -> "Non-obligatoire"
    "saving" -> "Épargne"
    "investment" -> "Investissement"
    "debt" -> "Remboursement de prêt"
    else -> obligation
}

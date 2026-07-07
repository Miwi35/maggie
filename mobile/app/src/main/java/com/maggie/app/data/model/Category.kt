package com.maggie.app.data.model

import kotlinx.serialization.Serializable

@Serializable
data class Category(
    val id: String,
    val name: String,
    val obligation: String = "optional",
    val parentId: String? = null,
    val color: String? = null,
    val icon: String? = null,
)

/** Human-readable French label for an obligation flag code. */
fun obligationLabel(obligation: String): String = when (obligation) {
    "mandatory" -> "Obligatoire"
    "optional" -> "Non-obligatoire"
    "saving" -> "Épargne"
    "investment" -> "Investissement"
    else -> obligation
}

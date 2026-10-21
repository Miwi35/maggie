package com.maggie.app.data.model

/** Where a salary figure comes from (`declared`, `expected`, `measured`, else unknown), in the owner's words (MAG-275). */
fun referenceSalarySourceLabel(source: String): String = when (source) {
    "declared" -> "déclaré sur le matelas"
    "expected" -> "attendu (opération récurrente)"
    "measured" -> "mesuré sur 12 mois"
    else -> "non renseigné"
}

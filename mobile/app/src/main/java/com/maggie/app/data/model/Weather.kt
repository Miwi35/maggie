package com.maggie.app.data.model

import kotlinx.serialization.Serializable

/** The day's weather (`GET /api/weather`, MAG-710); every figure may be missing, the card then skips it. */
@Serializable
data class Weather(
    val date: String? = null,
    val minC: Double? = null,
    val maxC: Double? = null,
    val rainMm: Double? = null,
    val snowCm: Double? = null,
    val rainProbability: Double? = null,
    val windKmh: Double? = null,
    val alerts: List<String> = emptyList(),
    val condition: String? = null,
) {
    /** « Éclaircies », « Pluie »…; `null` for a condition the app does not know. */
    val conditionLabel: String? get() = when (condition) {
        "clear" -> "Ensoleillé"
        "partly_cloudy" -> "Éclaircies"
        "cloudy" -> "Nuageux"
        "fog" -> "Brouillard"
        "drizzle" -> "Bruine"
        "rain" -> "Pluie"
        "showers" -> "Averses"
        "snow" -> "Neige"
        "storm" -> "Orages"
        else -> null
    }

    /** « 4 mm de pluie » from 1 mm, « 3 cm de neige » from 1 cm; snow wins when both. */
    val quantityLabel: String? get() = when {
        (snowCm ?: 0.0) >= 1.0 -> "${snowCm!!.toInt()} cm de neige"
        (rainMm ?: 0.0) >= 1.0 -> "${rainMm!!.toInt()} mm de pluie"
        else -> null
    }

    /** « 12° » */
    fun degrees(value: Double?): String? = value?.let { "${Math.round(it)}°" }
}

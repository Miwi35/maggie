package com.maggie.app.ui.screens.health

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Pool
import androidx.compose.material.icons.filled.SportsScore
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.ui.graphics.vector.ImageVector

/** `WorkoutType` of the API with its French word and the icon of its round tile (MAG-705). */
enum class WorkoutType(val key: String, val label: String, val icon: ImageVector) {
    RUN("run", "Course à pied", Icons.AutoMirrored.Filled.DirectionsRun),
    WALK("walk", "Marche", Icons.AutoMirrored.Filled.DirectionsWalk),
    BIKE("bike", "Vélo", Icons.AutoMirrored.Filled.DirectionsBike),
    SWIM("swim", "Natation", Icons.Default.Pool),
    STRENGTH("strength", "Musculation", Icons.Default.FitnessCenter),
    CLIMBING("climbing", "Escalade", Icons.Default.Terrain),
    OTHER("other", "Autre", Icons.Default.SportsScore),
    ;

    companion object {
        /** An unknown key reads as « Autre »: a type added by the API must not blank the row. */
        fun of(key: String?): WorkoutType = entries.firstOrNull { it.key == key } ?: OTHER
    }
}

/** `WorkoutFeeling`: how the session felt. */
enum class WorkoutFeeling(val key: String, val label: String) {
    VERY_GOOD("very_good", "Très bien"),
    GOOD("good", "Bien"),
    HARD("hard", "Dur"),
    VERY_HARD("very_hard", "Très dur"),
    ;

    companion object {
        fun of(key: String?): WorkoutFeeling? = entries.firstOrNull { it.key == key }
    }
}

/** `WorkoutSource`: where the session comes from, as the pill of a row says it. */
fun workoutSourceLabel(source: String): String = when (source) {
    "manual" -> "Saisie"
    "watch" -> "Montre"
    "activity" -> "Activité Sport"
    "estimated" -> "Estimée"
    "planned" -> "Prévue"
    else -> source
}

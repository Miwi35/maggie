package com.maggie.app.data.model

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * A project of the owner (MAG-519): what it costs, how much it counts for his wellbeing, when it
 * should happen, and the projects it waits for. Read from `GET /api/finance/projects`, ordered by
 * [position] (0 first, the priority order the owner sets by dragging).
 *
 * [dependsOn] holds IRIs on the API Platform resource (`/api/finance/projects/{id}`) and plain ids
 * in the answer of `POST /api/finance/projects/reorder`: [dependencyIds] reads both.
 * [unmetDependencies] are plain ids — the projects this one waits for that come after it.
 */
@Serializable
data class Project(
    val id: String,
    val name: String,
    val amountCents: Int = 0,
    val currency: String = "EUR",
    val wellbeing: String = ProjectWellbeing.MEDIUM.key,
    val wellbeingScore: Int = 0,
    val position: Int = 0,
    val dateMode: String = ProjectDateMode.NONE.key,
    val targetOn: String? = null,
    val dependsOn: List<String> = emptyList(),
    val note: String? = null,
    val unmetDependencies: List<String> = emptyList(),
) {
    val dependencyIds: List<String> get() = dependsOn.map { it.substringAfterLast('/') }
}

/** How much a project counts for the owner's wellbeing, with the score the API derives from it. */
enum class ProjectWellbeing(val key: String, val label: String, val score: Int) {
    ESSENTIAL("essential", "Indispensable", 4),
    HIGH("high", "Élevé", 3),
    MEDIUM("medium", "Moyen", 2),
    COMFORT("comfort", "Confort", 1),
    ;

    companion object {
        fun of(key: String): ProjectWellbeing = entries.firstOrNull { it.key == key } ?: MEDIUM
    }
}

/** No date, a locked date, or a date it must be done by. */
enum class ProjectDateMode(val key: String, val label: String) {
    NONE("none", "Sans date"),
    FIXED("fixed", "Date fixe"),
    DEADLINE("deadline", "Date limite"),
    ;

    val needsDate: Boolean get() = this != NONE

    companion object {
        fun of(key: String): ProjectDateMode = entries.firstOrNull { it.key == key } ?: NONE
    }
}

fun projectIri(id: String): String = "/api/finance/projects/$id"

private val PROJECT_DATE = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.FRANCE)

/** « Sans date », « Date fixe · 1 mars 2027 », « Date limite · 15 janvier 2027 ». */
fun projectDateLabel(project: Project): String {
    val mode = ProjectDateMode.of(project.dateMode)
    val date = project.targetOn?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }
    if (mode == ProjectDateMode.NONE || date == null) return mode.label
    return "${mode.label} · ${PROJECT_DATE.format(date)}"
}

/** « après Déménagement », « après Déménagement, Voiture »; null for a project that waits for none. */
fun projectDependenciesLabel(project: Project, all: List<Project>): String? {
    val names = project.dependencyIds.mapNotNull { id -> all.firstOrNull { it.id == id }?.name }
    return if (names.isEmpty()) null else "après ${names.joinToString(", ")}"
}

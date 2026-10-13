package com.maggie.app.data.model

import kotlinx.serialization.Serializable
import java.time.LocalDate

@Serializable
data class Task(
    val id: String,
    val title: String,
    /** The notes, as Google Tasks calls them. */
    val description: String? = null,
    val priority: String = "medium",
    val criticality: String = "low",
    /** A day, `YYYY-MM-DD`, with no time and no zone (MAG-404). */
    val dueDate: String? = null,
    val completedAt: String? = null,
    /** The parent's IRI (`/api/tasks/{id}`) on a subtask; one level only. */
    val parent: String? = null,
    /** Google's order among siblings, compared as a plain string. */
    val position: String? = null,
    /** Read only: what Google attached (a mail, a document). */
    val links: List<TaskLink> = emptyList(),
) {
    val isDone: Boolean get() = completedAt != null

    val isSubtask: Boolean get() = parent != null

    val iri: String get() = iriOf(id)

    /** The due day, or null when there is none or the API sent something else. */
    val dueDay: LocalDate? get() = dueDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    companion object {
        fun iriOf(id: String): String = "/api/tasks/$id"

        /** Siblings as the API orders them: position as plain strings, then id; no position last. */
        val SIBLING_ORDER: Comparator<Task> =
            compareBy<Task, String?>(nullsLast()) { it.position }.thenBy { it.id }
    }
}

@Serializable
data class TaskLink(
    val type: String? = null,
    val description: String? = null,
    val link: String,
)

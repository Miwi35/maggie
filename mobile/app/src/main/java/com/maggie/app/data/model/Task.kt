package com.maggie.app.data.model

import kotlinx.serialization.Serializable

@Serializable
data class Task(
    val id: String,
    val title: String,
    val description: String? = null,
    val priority: String = "medium",
    val criticality: String = "low",
    val dueDate: String? = null,
    val completedAt: String? = null,
) {
    val isDone: Boolean get() = completedAt != null
}

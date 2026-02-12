package com.maggie.app.data.model

import kotlinx.serialization.Serializable

@Serializable
data class Task(
    val id: String,
    val name: String,
    val description: String? = null,
    val priority: String = "medium",
    val criticality: String = "low",
    val dueDate: String? = null,
    val doneDate: String? = null,
) {
    val isDone: Boolean get() = doneDate != null
}

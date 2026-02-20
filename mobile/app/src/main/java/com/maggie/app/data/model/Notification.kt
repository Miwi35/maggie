package com.maggie.app.data.model

import kotlinx.serialization.Serializable

@Serializable
data class Notification(
    val id: String,
    val type: String = "reminder",
    val title: String,
    val body: String? = null,
    val relatedEntityIri: String? = null,
    val readAt: String? = null,
    val createdAt: String? = null,
) {
    val isRead: Boolean get() = readAt != null
}

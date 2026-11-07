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
    val expiresAt: String? = null,
    val createdAt: String? = null,
    /** The question a `question` notification puts (MAG-717); null on every other. */
    val interaction: NotificationInteraction? = null,
) {
    val isRead: Boolean get() = readAt != null
}

/** `kind` is `choice`; [questionId] is what `POST /agent/questions/{id}/answer` takes. */
@Serializable
data class NotificationInteraction(
    val kind: String? = null,
    val options: List<String> = emptyList(),
    val multiple: Boolean = false,
    val questionId: String? = null,
)

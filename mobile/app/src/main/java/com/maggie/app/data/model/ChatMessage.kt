package com.maggie.app.data.model

import kotlinx.serialization.Serializable

@Serializable
data class ChatMessage(
    val id: String = "",
    val role: String,
    val content: String,
    val createdAt: String = "",
    /** The thread the message was filed in, `null` for one that belongs to none (MAG-342). */
    val contextId: String? = null,
)

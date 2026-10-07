package com.maggie.app.data.model

import kotlinx.serialization.Serializable

@Serializable
data class ChatMessage(
    val id: String = "",
    val role: String,
    val content: String,
    val createdAt: String = "",
    /**
     * The question was sent with a screenshot (MAG-214). The image itself is never
     * stored anywhere: after a reload the bubble can only say there was one.
     */
    val hasImage: Boolean = false,
)

package com.maggie.app.data.model

import kotlinx.serialization.Serializable

@Serializable
data class ChatMessage(
    val id: String = "",
    val role: String,
    val content: String,
    val createdAt: String = "",
)

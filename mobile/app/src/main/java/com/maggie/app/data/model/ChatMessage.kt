package com.maggie.app.data.model

data class ChatMessage(
    val role: String, // "user" or "assistant"
    val content: String,
)

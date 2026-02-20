package com.maggie.app.data.model

import kotlinx.serialization.Serializable

@Serializable
data class Proaction(
    val id: String,
    val userId: String? = null,
    val prompt: String,
    val status: String = "pending",
    val scheduledAt: String? = null,
    val response: String? = null,
    val error: String? = null,
    val createdAt: String? = null,
    val completedAt: String? = null,
)

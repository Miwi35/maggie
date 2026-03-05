package com.maggie.app.data.model

import kotlinx.serialization.Serializable

@Serializable
data class Context(
    val id: String,
    val label: String,
    val status: String = "active",
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

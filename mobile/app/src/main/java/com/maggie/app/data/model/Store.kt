package com.maggie.app.data.model

import kotlinx.serialization.Serializable

@Serializable
data class Store(
    val id: String,
    val name: String,
    val description: String? = null,
    val visitOrder: Int = 0,
)

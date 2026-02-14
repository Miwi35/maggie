package com.maggie.app.data.model

import kotlinx.serialization.Serializable

@Serializable
data class User(
    val id: String,
    val name: String? = null,
    val email: String? = null,
    val avatar: String? = null,
    val googleTaskListId: String? = null,
)

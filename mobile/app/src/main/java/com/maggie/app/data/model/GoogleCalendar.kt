package com.maggie.app.data.model

import kotlinx.serialization.Serializable

@Serializable
data class GoogleCalendar(
    val id: String,
    val summary: String,
    val primary: Boolean = false,
    val backgroundColor: String? = null,
)

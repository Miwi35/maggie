package com.maggie.app.data.model

import kotlinx.serialization.Serializable

@Serializable
data class GoogleCalendar(
    val id: String,
    /**
     * The name the agenda will carry once imported — the name Google displays,
     * and "Défaut" for the primary calendar (MAG-148). Shown instead of
     * [summary], which for the primary calendar is the account holder's name.
     */
    val name: String,
    val summary: String,
    val primary: Boolean = false,
    val backgroundColor: String? = null,
)

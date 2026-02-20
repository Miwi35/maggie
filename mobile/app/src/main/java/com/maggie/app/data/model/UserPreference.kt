package com.maggie.app.data.model

import kotlinx.serialization.Serializable

@Serializable
data class UserPreference(
    val id: String = "",
    val theme: String = "system",
    val locale: String = "fr",
    val timezone: String = "Europe/Paris",
    val defaultCalendarView: String = "month",
    val enabledAgendaIds: List<String> = emptyList(),
    val notificationsEnabled: Boolean = true,
)

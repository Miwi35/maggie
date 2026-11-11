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
    /** The two daily rendezvous Maggie lays down (MAG-787), « HH:MM » in the user's timezone. */
    val morningDigestEnabled: Boolean = true,
    val morningDigestTime: String = "07:00",
    val eveningReviewEnabled: Boolean = true,
    val eveningReviewTime: String = "21:30",
)

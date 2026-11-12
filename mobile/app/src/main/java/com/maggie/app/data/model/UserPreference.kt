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
    /** Working hours (MAG-790), « HH:MM » in the user's timezone and ISO days (1 = Monday … 7): Maggie's tone follows them. */
    val workStartTime: String = "09:00",
    val workEndTime: String = "18:00",
    val workDays: List<Int> = listOf(1, 2, 3, 4, 5),
)

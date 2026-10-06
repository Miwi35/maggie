package com.maggie.app.data.model

import kotlinx.serialization.Serializable

@Serializable
data class Event(
    val id: String,
    val summary: String,
    val description: String? = null,
    val location: String? = null,
    val allDay: Boolean = false,
    val startAt: String,
    val endAt: String,
    val timeZone: String = "Europe/Paris",
    val status: String = "confirmed",
    val rrule: String? = null,
    val recurringEvent: String? = null,
    val originalStartAt: String? = null,
    val reminders: EventReminders? = null,
    val agenda: String? = null,
)

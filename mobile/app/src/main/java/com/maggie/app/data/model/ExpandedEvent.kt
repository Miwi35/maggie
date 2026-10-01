package com.maggie.app.data.model

/**
 * Flattened event occurrence — either a real event or a virtual RRULE occurrence.
 */
data class ExpandedEvent(
    val id: String,
    val summary: String,
    val description: String? = null,
    val location: String? = null,
    val allDay: Boolean = false,
    val startAt: String,
    val endAt: String,
    val timeZone: String = "Europe/Paris",
    val status: String = "confirmed",
    val isVirtualOccurrence: Boolean = false,
    val masterEventId: String? = null,
    val masterRrule: String? = null,
    val masterStartAt: String? = null,
    val originalStartAt: String? = null,
    val agendaIri: String? = null,
    val agendaColor: String? = null,
    val agendaName: String? = null,
)

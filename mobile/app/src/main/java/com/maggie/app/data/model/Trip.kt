package com.maggie.app.data.model

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** One leg of a [Trip]: [kind] is `walk`, `drive`, `transit` or `bicycle`; [label] names a line or a stop when there is one. */
@Serializable
data class TripStep(
    val kind: String,
    val minutes: Int,
    val label: String? = null,
)

/**
 * The trip Maggie chose to an event (« Partir à l'heure », MAG-654): at most one per event. [departAt]
 * is when to leave; a new value is a new departure alert. The origin is a copy — « ma position » moves,
 * the trip keeps where it was computed from.
 */
@Serializable
data class Trip(
    val id: String,
    /** The event's IRI, `/api/events/{id}`. */
    val event: String,
    val mode: String = MODE_DRIVE,
    val steps: List<TripStep> = emptyList(),
    val durationMinutes: Int = 0,
    val departAt: String,
    val navigationUrl: String = "",
    val originKind: String = "",
    val originLabel: String = "",
    val originDecidedBy: String = "",
    val computedAt: String? = null,
) {
    val eventId: String get() = event.substringAfterLast('/')

    val departInstant: Instant? get() = runCatching { OffsetDateTime.parse(departAt).toInstant() }.getOrNull()

    /** « 13:34 », read in [zone]. */
    fun departTime(zone: ZoneId): String =
        departInstant?.atZone(zone)?.toLocalTime()?.format(HOUR_MINUTE).orEmpty()

    /** « Partir à 13:34 · depuis la maison » — « Parti à » once the time has passed. */
    fun headline(zone: ZoneId, now: Instant): String {
        val verb = if (departInstant?.isAfter(now) == false) "Parti" else "Partir"
        val time = departTime(zone)
        return if (originLabel.isBlank()) "$verb à $time" else "$verb à $time · depuis $originLabel"
    }

    companion object {
        const val MODE_DRIVE = "drive"
        private val HOUR_MINUTE: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}

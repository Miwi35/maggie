package com.maggie.app.data.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Flattened event occurrence — either a real event or a virtual RRULE occurrence.
 *
 * Timed: [startAt]/[endAt] are instants. All-day: [startDate]/[endDate] are the
 * API's dates, the end exclusive, and the instants are null (MAG-382).
 */
@Serializable
data class ExpandedEvent(
    val id: String,
    val summary: String,
    val description: String? = null,
    val location: String? = null,
    val allDay: Boolean = false,
    val startAt: String? = null,
    val endAt: String? = null,
    @Serializable(with = IsoDateSerializer::class) val startDate: LocalDate? = null,
    @Serializable(with = IsoDateSerializer::class) val endDate: LocalDate? = null,
    val timeZone: String = "Europe/Paris",
    val status: String = "confirmed",
    val isVirtualOccurrence: Boolean = false,
    val masterEventId: String? = null,
    val masterRrule: String? = null,
    /** The series' first occurrence, as an occurrence key (see [occurrenceKey]). */
    val masterStartAt: String? = null,
    val originalStartAt: String? = null,
    val reminders: EventReminders? = null,
    val agendaIri: String? = null,
    val agendaColor: String? = null,
    val agendaName: String? = null,
    val recurrenceUnreadable: Boolean = false,
) {
    /** Sorts events of one day: an ISO date sorts before any instant of that date. */
    val sortKey: String get() = startAt ?: startDate?.toString().orEmpty()

    /**
     * The days the event covers in [zone]. An all-day event covers the days d with
     * startDate <= d < endDate — no zone ever applies to a date. Null when the event
     * carries neither.
     */
    fun days(zone: ZoneId): ClosedRange<LocalDate>? {
        if (allDay && startDate != null) {
            val last = endDate?.let(::lastDayOf) ?: startDate
            return startDate..maxOf(startDate, last)
        }
        val start = startAt?.let { Instant.parse(it).atZone(zone).toLocalDate() } ?: return null
        val end = endAt?.let { Instant.parse(it).atZone(zone).toLocalDate() } ?: start
        return start..maxOf(start, end)
    }

    /** The first day the event covers in [zone]. */
    fun firstDay(zone: ZoneId): LocalDate? = days(zone)?.start
}

/**
 * The key that names one occurrence of a series, for its exceptions: the instant of
 * a timed occurrence, midnight UTC of the date of an all-day one — a key, not a time.
 */
fun occurrenceKey(date: LocalDate): String = "${date}T00:00:00+00:00"

/** The date an all-day occurrence key names (see [occurrenceKey]). */
fun occurrenceDate(key: String): LocalDate = Instant.parse(key).atZone(ZoneId.of("UTC")).toLocalDate()

/** A day as `YYYY-MM-DD`, with no zone — how the open event is saved when the phone is turned (MAG-263). */
object IsoDateSerializer : KSerializer<LocalDate> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("IsoDate", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: LocalDate) = encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder): LocalDate = LocalDate.parse(decoder.decodeString())
}

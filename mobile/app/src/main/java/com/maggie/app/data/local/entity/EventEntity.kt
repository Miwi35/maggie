package com.maggie.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.maggie.app.data.model.Event
import com.maggie.app.data.model.EventReminders
import kotlinx.serialization.json.Json

enum class SyncStatus {
    SYNCED,
    PENDING_CREATE,
    PENDING_UPDATE,
    PENDING_DELETE,
}

@Entity(tableName = "events")
data class EventEntity(
    @PrimaryKey
    val id: String,
    val summary: String,
    val description: String?,
    val location: String?,
    val allDay: Boolean,
    /** Null for an all-day event, which has [startDate]/[endDate] instead (MAG-382). */
    val startAt: String?,
    val endAt: String?,
    /** `YYYY-MM-DD`, the end exclusive as the API has it; null for a timed event. */
    val startDate: String?,
    val endDate: String?,
    val timeZone: String,
    val status: String,
    val rrule: String?,
    val recurringEvent: String?,
    val originalStartAt: String?,
    /**
     * The reminders as the API serves them, kept as the JSON itself.
     *
     * A column per delay would mean a schema per number of reminders; the shape
     * is Google's and the app never reads inside it except through
     * `remindersOf`/`remindersFrom`.
     */
    val reminders: String?,
    val agenda: String?,
    val syncStatus: SyncStatus = SyncStatus.SYNCED,
) {
    fun toModel(): Event = Event(
        id = id,
        summary = summary,
        description = description,
        location = location,
        allDay = allDay,
        startAt = startAt,
        endAt = endAt,
        startDate = startDate,
        endDate = endDate,
        timeZone = timeZone,
        status = status,
        rrule = rrule,
        recurringEvent = recurringEvent,
        originalStartAt = originalStartAt,
        reminders = reminders?.let { JSON.decodeFromString<EventReminders>(it) },
        agenda = agenda,
    )

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }

        fun fromModel(event: Event, syncStatus: SyncStatus = SyncStatus.SYNCED): EventEntity =
            EventEntity(
                id = event.id,
                summary = event.summary,
                description = event.description,
                location = event.location,
                allDay = event.allDay,
                startAt = event.startAt,
                endAt = event.endAt,
                startDate = event.startDate,
                endDate = event.endDate,
                timeZone = event.timeZone,
                status = event.status,
                rrule = event.rrule,
                recurringEvent = event.recurringEvent,
                originalStartAt = event.originalStartAt,
                reminders = event.reminders?.let { JSON.encodeToString(EventReminders.serializer(), it) },
                agenda = event.agenda,
                syncStatus = syncStatus,
            )
    }
}

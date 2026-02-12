package com.maggie.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.maggie.app.data.model.Event

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
    val startAt: String,
    val endAt: String,
    val timeZone: String,
    val status: String,
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
        timeZone = timeZone,
        status = status,
    )

    companion object {
        fun fromModel(event: Event, syncStatus: SyncStatus = SyncStatus.SYNCED): EventEntity =
            EventEntity(
                id = event.id,
                summary = event.summary,
                description = event.description,
                location = event.location,
                allDay = event.allDay,
                startAt = event.startAt,
                endAt = event.endAt,
                timeZone = event.timeZone,
                status = event.status,
                syncStatus = syncStatus,
            )
    }
}

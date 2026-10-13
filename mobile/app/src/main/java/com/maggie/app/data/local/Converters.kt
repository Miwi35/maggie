package com.maggie.app.data.local

import androidx.room.TypeConverter
import com.maggie.app.data.local.entity.SyncStatus
import com.maggie.app.data.model.TaskLink
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

class Converters {
    @TypeConverter
    fun fromSyncStatus(value: SyncStatus): String = value.name

    @TypeConverter
    fun toSyncStatus(value: String): SyncStatus = SyncStatus.valueOf(value)

    // A task's links are read whole and never queried: one JSON column is enough.
    @TypeConverter
    fun fromTaskLinks(value: List<TaskLink>): String = json.encodeToString(LINKS, value)

    @TypeConverter
    fun toTaskLinks(value: String): List<TaskLink> =
        runCatching { json.decodeFromString(LINKS, value) }.getOrDefault(emptyList())

    // Typed explicitly: KSP reads this class before the serialization plugin generates `serializer()`.
    private companion object {
        val json: Json = Json { ignoreUnknownKeys = true }
        val LINKS: KSerializer<List<TaskLink>> = ListSerializer(TaskLink.serializer())
    }
}

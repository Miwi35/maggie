package com.maggie.app.data.repository

import com.maggie.app.data.api.EventCreateRequest
import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.local.dao.EventDao
import com.maggie.app.data.local.entity.EventEntity
import com.maggie.app.data.local.entity.SyncStatus
import com.maggie.app.data.model.Event
import com.maggie.app.util.rethrowCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class EventRepository(
    private val apiService: MaggieApiService,
    private val eventDao: EventDao,
) {
    fun observeEvents(): Flow<List<Event>> =
        eventDao.observeAll().map { entities ->
            entities.map { it.toModel() }
        }

    suspend fun refreshEvents(): Result<List<Event>> = runCatching {
        val now = LocalDate.now()
        val afterDate = now.minusMonths(3).format(DateTimeFormatter.ISO_LOCAL_DATE)
        val beforeDate = now.plusYears(1).format(DateTimeFormatter.ISO_LOCAL_DATE)

        val serverEvents = apiService.getEvents(afterDate = afterDate, beforeDate = beforeDate)
        val serverIds = serverEvents.map { it.id }.toSet()

        eventDao.upsertAll(serverEvents.map { EventEntity.fromModel(it) })

        val localSyncedIds = eventDao.getIdsByStatus(SyncStatus.SYNCED)
        val staleIds = localSyncedIds.filter { it !in serverIds }
        if (staleIds.isNotEmpty()) {
            eventDao.deleteSyncedByIds(staleIds)
        }

        serverEvents
    }

    suspend fun getEvents(): Result<List<Event>> = runCatching {
        eventDao.getAll().map { it.toModel() }
    }

    /** The cached event, else the server's (a notification can name an event the cache has not synced yet). Null when neither has it. */
    suspend fun findEvent(id: String): Event? =
        eventDao.getById(id)?.toModel()
            ?: runCatching { apiService.getEvent(id) }.rethrowCancellation().getOrNull()?.also {
                eventDao.upsertAll(listOf(EventEntity.fromModel(it)))
            }

    suspend fun getRecurringBefore(before: String): List<Event> {
        return apiService.getRecurringEventsBefore(before)
    }

    suspend fun createEvent(request: EventCreateRequest): Result<Event> = runCatching {
        val event = apiService.createEvent(request)
        eventDao.upsertAll(listOf(EventEntity.fromModel(event)))
        event
    }

    suspend fun updateEvent(id: String, data: JsonObject): Result<Event> = runCatching {
        val event = apiService.updateEvent(id, data)
        eventDao.upsertAll(listOf(EventEntity.fromModel(event)))
        event
    }

    suspend fun deleteEvent(id: String): Result<Unit> = runCatching {
        apiService.deleteEvent(id)
        eventDao.deleteById(id)
    }
}

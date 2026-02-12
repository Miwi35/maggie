package com.maggie.app.data.repository

import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.local.dao.EventDao
import com.maggie.app.data.local.entity.EventEntity
import com.maggie.app.data.local.entity.SyncStatus
import com.maggie.app.data.model.Event
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class EventRepository(
    private val apiService: MaggieApiService,
    private val eventDao: EventDao,
) {
    /** Reactive stream of cached events — the UI observes this. */
    fun observeEvents(): Flow<List<Event>> =
        eventDao.observeAll().map { entities ->
            entities.map { it.toModel() }
        }

    /**
     * Windowed fetch: 3 months back, 1 year forward.
     * Upserts server events, then removes SYNCED-only events
     * within the window that are no longer on the server.
     */
    suspend fun refreshEvents(): Result<List<Event>> = runCatching {
        val now = LocalDate.now()
        val afterDate = now.minusMonths(3).format(DateTimeFormatter.ISO_LOCAL_DATE)
        val beforeDate = now.plusYears(1).format(DateTimeFormatter.ISO_LOCAL_DATE)

        val serverEvents = apiService.getEvents(afterDate = afterDate, beforeDate = beforeDate)
        val serverIds = serverEvents.map { it.id }.toSet()

        // Upsert all server events as SYNCED
        eventDao.upsertAll(serverEvents.map { EventEntity.fromModel(it) })

        // Remove SYNCED events that the server no longer returns (deleted/out of window)
        val localSyncedIds = eventDao.getIdsByStatus(SyncStatus.SYNCED)
        val staleIds = localSyncedIds.filter { it !in serverIds }
        if (staleIds.isNotEmpty()) {
            eventDao.deleteSyncedByIds(staleIds)
        }

        serverEvents
    }

    /** Read from cache (backward compat). */
    suspend fun getEvents(): Result<List<Event>> = runCatching {
        eventDao.getAll().map { it.toModel() }
    }
}

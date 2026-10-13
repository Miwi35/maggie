package com.maggie.app.data.repository

import com.maggie.app.data.api.EventCreateRequest
import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.local.dao.EventDao
import com.maggie.app.data.local.entity.EventEntity
import com.maggie.app.data.local.entity.FetchedPeriodEntity
import com.maggie.app.data.local.entity.SyncStatus
import com.maggie.app.data.model.Event
import com.maggie.app.util.DayPeriod
import com.maggie.app.util.rethrowCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonObject
import java.time.Clock
import java.time.LocalDate
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/** How long a stretch of calendar read in full is served from the cache before the API is asked again (MAG-430). */
val PERIOD_CACHE_TTL: Duration = 1.hours

/** More events than this in one push burst (a Google sync, an import) are re-read by period, not one by one. */
const val PUSH_BULK_LIMIT = 20

/** How many cached periods a bulk change re-reads at once; the older ones are dropped and re-read when visited. */
private const val BULK_RELOAD_PERIODS = 3

class EventRepository(
    private val apiService: MaggieApiService,
    private val eventDao: EventDao,
    private val clock: Clock = Clock.systemUTC(),
    private val periodCacheTtl: Duration = PERIOD_CACHE_TTL,
) {
    fun observeEvents(): Flow<List<Event>> =
        eventDao.observeAll().map { entities ->
            entities.map { it.toModel() }
        }

    /**
     * Makes sure the cache holds every event of [period] — the ones that meet it, one request, no
     * pagination — and the recurring ones started before its end. A period already read within
     * [periodCacheTtl] (itself, or a longer one around it) is not read again unless [force]: the
     * pushes keep the cache current in between ([applyPushes]).
     */
    suspend fun loadPeriod(period: DayPeriod, force: Boolean = false): Result<Unit> = runCatching {
        val now = clock.millis()
        val firstDay = period.firstDay.toString()
        val endDay = period.endDay.toString()
        val validSince = now - periodCacheTtl.inWholeMilliseconds
        if (!force && eventDao.countFetchedPeriodsCovering(firstDay, endDay, validSince) > 0) return@runCatching

        val range = period.asRange()
        val inPeriod = apiService.getEvents(range.start.toString(), range.end.toString())
        val recurring = apiService.getRecurringEventsBefore(range.end.toString())
        val fetched = (inPeriod + recurring).distinctBy { it.id }
        val fetchedIds = fetched.mapTo(mutableSetOf()) { it.id }

        eventDao.upsertAll(fetched.map { EventEntity.fromModel(it) })
        // What the server no longer has for this period, and only for it: the rest of the cache was not asked about.
        eventDao.getSyncedIdsOfPeriod(firstDay, endDay)
            .filter { it !in fetchedIds }
            .chunked(DELETE_CHUNK)
            .forEach { eventDao.deleteSyncedByIds(it) }
        eventDao.deleteFetchedPeriodsBefore(validSince)
        eventDao.upsertFetchedPeriod(FetchedPeriodEntity(firstDay, endDay, now))
    }.rethrowCancellation()

    /**
     * Applies what the push announced to the cache: a deleted event leaves it, any other is read
     * (one request each — the payload carries a few fields only, not the rule, the agenda or the
     * reminders) and replaces its row. A burst of more than [PUSH_BULK_LIMIT] events is a sync: the
     * periods read lately are read again instead, the older ones forgotten.
     */
    suspend fun applyPushes(pushes: Map<String, Boolean>) {
        if (pushes.size > PUSH_BULK_LIMIT) {
            reloadCachedPeriods()
            return
        }
        for ((id, deleted) in pushes) {
            if (deleted) {
                eventDao.deleteById(id)
                continue
            }
            val event = runCatching { apiService.getEvent(id) }.rethrowCancellation().getOrNull() ?: continue
            eventDao.upsertAll(listOf(EventEntity.fromModel(event)))
        }
    }

    /** The next [loadPeriod] of any period asks the API: used when the pushes were not listened to. */
    suspend fun forgetFetchedPeriods() = eventDao.deleteFetchedPeriods()

    private suspend fun reloadCachedPeriods() {
        val periods = eventDao.getFetchedPeriodsSince(clock.millis() - periodCacheTtl.inWholeMilliseconds)
        eventDao.deleteFetchedPeriods()
        periods.take(BULK_RELOAD_PERIODS).forEach {
            loadPeriod(DayPeriod(LocalDate.parse(it.firstDay), LocalDate.parse(it.endDay)), force = true)
        }
    }

    /** The agenda is gone: so are its events. */
    suspend fun dropEventsOfAgenda(agendaId: String) {
        eventDao.deleteSyncedOfAgenda("/api/agendas/$agendaId")
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

// SQLite caps the variables of one statement at 999.
private const val DELETE_CHUNK = 500

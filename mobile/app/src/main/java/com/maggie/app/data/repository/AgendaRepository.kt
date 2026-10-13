package com.maggie.app.data.repository

import com.maggie.app.data.api.AgendaCreateRequest
import com.maggie.app.data.api.GoogleCalendarImportRequest
import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.local.dao.AgendaDao
import com.maggie.app.data.local.entity.AgendaEntity
import com.maggie.app.data.model.Agenda
import com.maggie.app.data.model.GoogleCalendar
import com.maggie.app.util.rethrowCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Clock
import kotlin.time.Duration

class AgendaRepository(
    private val apiService: MaggieApiService,
    private val agendaDao: AgendaDao,
    private val clock: Clock = Clock.systemUTC(),
    private val periodCacheTtl: Duration = PERIOD_CACHE_TTL,
) {
    @Volatile
    private var lastReadAt: Long? = null

    fun observeAgendas(): Flow<List<Agenda>> =
        agendaDao.observeAll().map { entities ->
            entities.map { it.toModel() }
        }

    suspend fun refreshAgendas(): Result<List<Agenda>> = runCatching {
        val serverAgendas = apiService.getAgendas()
        agendaDao.deleteAll()
        agendaDao.upsertAll(serverAgendas.map { AgendaEntity.fromModel(it) })
        lastReadAt = clock.millis()
        serverAgendas
    }

    /**
     * The agendas, from the cache while they were read in full less than [periodCacheTtl] ago (the
     * pushes keep them current, [applyPushes]); read again past that, or when [force]d.
     */
    suspend fun loadAgendas(force: Boolean = false): Result<List<Agenda>> {
        val readAt = lastReadAt
        if (!force && readAt != null && clock.millis() - readAt < periodCacheTtl.inWholeMilliseconds) {
            val cached = runCatching { agendaDao.getAll().map { it.toModel() } }.getOrNull()
            if (!cached.isNullOrEmpty()) return Result.success(cached)
        }
        return refreshAgendas()
    }

    fun forgetLastRead() {
        lastReadAt = null
    }

    /**
     * Applies what the push announced: a deleted agenda leaves the cache, any other is read and
     * replaces its row. Returns the ids of the agendas deleted, whose events go with them.
     */
    suspend fun applyPushes(pushes: Map<String, Boolean>): List<String> {
        val deleted = mutableListOf<String>()
        for ((id, gone) in pushes) {
            if (gone) {
                agendaDao.deleteById(id)
                deleted += id
                continue
            }
            val agenda = runCatching { apiService.getAgenda(id) }.rethrowCancellation().getOrNull() ?: continue
            agendaDao.upsertAll(listOf(AgendaEntity.fromModel(agenda)))
        }
        return deleted
    }

    suspend fun getAgendas(): List<Agenda> {
        val cached = agendaDao.getAll()
        if (cached.isNotEmpty()) return cached.map { it.toModel() }
        return apiService.getAgendas().also { agendas ->
            agendaDao.upsertAll(agendas.map { AgendaEntity.fromModel(it) })
        }
    }

    suspend fun createAgenda(request: AgendaCreateRequest): Result<Agenda> = runCatching {
        val agenda = apiService.createAgenda(request)
        agendaDao.upsertAll(listOf(AgendaEntity.fromModel(agenda)))
        agenda
    }

    /** The server demotes the previous default, so the whole list is reloaded rather than one row patched. */
    suspend fun setDefaultAgenda(id: String): Result<List<Agenda>> = runCatching {
        apiService.updateAgenda(id, buildJsonObject { put("isDefault", true) })
        refreshAgendas().getOrThrow()
    }

    suspend fun deleteAgenda(id: String): Result<Unit> = runCatching {
        apiService.deleteAgenda(id)
        agendaDao.deleteById(id)
    }

    suspend fun getGoogleCalendars(): Result<List<GoogleCalendar>> = runCatching {
        apiService.getGoogleCalendars()
    }

    suspend fun importGoogleCalendar(request: GoogleCalendarImportRequest): Result<Agenda> = runCatching {
        val agenda = apiService.importGoogleCalendar(request)
        agendaDao.upsertAll(listOf(AgendaEntity.fromModel(agenda)))
        agenda
    }

    suspend fun exportToGoogleCalendar(agendaId: String): Result<Unit> = runCatching {
        apiService.exportToGoogleCalendar(agendaId)
    }
}

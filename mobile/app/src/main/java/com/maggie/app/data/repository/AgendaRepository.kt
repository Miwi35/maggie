package com.maggie.app.data.repository

import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.local.dao.AgendaDao
import com.maggie.app.data.local.entity.AgendaEntity
import com.maggie.app.data.model.Agenda
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class AgendaRepository(
    private val apiService: MaggieApiService,
    private val agendaDao: AgendaDao,
) {
    fun observeAgendas(): Flow<List<Agenda>> =
        agendaDao.observeAll().map { entities ->
            entities.map { it.toModel() }
        }

    suspend fun refreshAgendas(): Result<List<Agenda>> = runCatching {
        val serverAgendas = apiService.getAgendas()
        agendaDao.deleteAll()
        agendaDao.upsertAll(serverAgendas.map { AgendaEntity.fromModel(it) })
        serverAgendas
    }

    suspend fun getAgendas(): List<Agenda> {
        val cached = agendaDao.getAll()
        if (cached.isNotEmpty()) return cached.map { it.toModel() }
        return apiService.getAgendas().also { agendas ->
            agendaDao.upsertAll(agendas.map { AgendaEntity.fromModel(it) })
        }
    }
}

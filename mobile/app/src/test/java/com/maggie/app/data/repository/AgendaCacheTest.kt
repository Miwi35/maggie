package com.maggie.app.data.repository

import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.local.dao.AgendaDao
import com.maggie.app.data.local.entity.AgendaEntity
import com.maggie.app.data.model.Agenda
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.hours

class AgendaCacheTest {
    private class MutableClock(var now: Instant) : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
        override fun instant() = now
    }

    private val api = mockk<MaggieApiService>()
    private val dao = mockk<AgendaDao>(relaxed = true)
    private val clock = MutableClock(Instant.parse("2026-06-10T08:00:00Z"))
    private val agendas = AgendaRepository(api, dao, clock, 1.hours)
    private val work = Agenda(id = "W", name = "Work")

    @Test
    fun `agendas read within the hour are served by the cache`() = runTest {
        coEvery { api.getAgendas() } returns listOf(work)
        coEvery { dao.getAll() } returns listOf(AgendaEntity.fromModel(work))
        agendas.loadAgendas()

        clock.now = clock.now.plusSeconds(30 * 60)
        assertEquals(listOf(work), agendas.loadAgendas().getOrThrow())

        coVerify(exactly = 1) { api.getAgendas() }
    }

    @Test
    fun `agendas are read again after the hour, or when forced`() = runTest {
        coEvery { api.getAgendas() } returns listOf(work)
        coEvery { dao.getAll() } returns listOf(AgendaEntity.fromModel(work))
        agendas.loadAgendas()

        agendas.loadAgendas(force = true)
        coVerify(exactly = 2) { api.getAgendas() }

        clock.now = clock.now.plusSeconds(61 * 60)
        agendas.loadAgendas()
        coVerify(exactly = 3) { api.getAgendas() }
    }

    @Test
    fun `a push puts the agenda in the cache, a delete push removes it and names it`() = runTest {
        coEvery { api.getAgenda("W") } returns work

        val deleted = agendas.applyPushes(mapOf("W" to false, "G" to true))

        coVerify { dao.upsertAll(match { it.single().id == "W" }) }
        coVerify { dao.deleteById("G") }
        assertEquals(listOf("G"), deleted)
        coVerify(exactly = 0) { api.getAgendas() }
    }
}

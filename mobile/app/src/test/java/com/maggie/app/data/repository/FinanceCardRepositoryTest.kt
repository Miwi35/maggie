package com.maggie.app.data.repository

import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureEvent
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.model.AccountsTile
import com.maggie.app.data.model.FinanceDashboard
import com.maggie.app.data.model.FinanceTiles
import com.maggie.app.data.model.FinanceTodo
import com.maggie.app.data.model.TileAccount
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The reads of the Finance card (MAG-592): the dashboard of the current month, one request; the topics of the resources behind it wake the card.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FinanceCardRepositoryTest {
    private val api = mockk<MaggieApiService>()
    private val mercure = mockk<MercureService>()
    private val auth = mockk<AuthRepository> { coEvery { getUserId() } returns "01HUSER" }
    private val repository = FinanceCardRepository(api, mercure, auth) { LocalDate.of(2026, 10, 23) }

    private fun dashboard() = FinanceDashboard(
        year = 2026,
        month = 10,
        tiles = FinanceTiles(accounts = AccountsTile(accounts = listOf(TileAccount(id = "a1", name = "Courant")))),
        todo = FinanceTodo(toSortCount = 2),
    )

    @Test
    fun `it reads the dashboard of the current month, one request`() = runTest {
        coEvery { api.getFinanceDashboard(any(), any()) } returns dashboard()

        val state = repository.read().getOrThrow()

        coVerify(exactly = 1) { api.getFinanceDashboard(2026, 10) }
        assertEquals(2, state.toQualifyCount)
        assertEquals(1, state.accountCount)
    }

    @Test
    fun `a failing read is a failure`() = runTest {
        coEvery { api.getFinanceDashboard(any(), any()) } throws RuntimeException("offline")

        assertTrue(repository.read().isFailure)
    }

    private val topics = listOf(
        "transactions", "accounts", "envelopes", "categories", "loans", "safety_cushions",
        "finance/projects", "finance/scenarios", "finance/placements",
    )

    @Test
    fun `a change on a resource behind the dashboard wakes the card, bursts merged`() = runTest {
        topics.forEach { every { mercure.subscribe("/users/01HUSER/api/$it/{id}") } returns flowOf() }
        every { mercure.subscribe("/users/01HUSER/api/transactions/{id}") } returns flowOf(MercureEvent(id = "1"), MercureEvent(id = "2"))

        val changes = repository.changes().toList()

        assertEquals(1, changes.size)
        topics.forEach { verify(exactly = 1) { mercure.subscribe("/users/01HUSER/api/$it/{id}") } }
        verify(exactly = 0) { mercure.subscribe(match { it.contains("/home/") }) }
    }

    @Test
    fun `a project planned elsewhere wakes the card`() = runTest {
        val projects = MutableSharedFlow<MercureEvent>()
        topics.forEach { every { mercure.subscribe("/users/01HUSER/api/$it/{id}") } returns flowOf() }
        every { mercure.subscribe("/users/01HUSER/api/finance/projects/{id}") } returns projects
        var woken = 0
        val job = launch { repository.changes().collect { woken++ } }
        runCurrent()

        projects.emit(MercureEvent(id = "1"))
        advanceUntilIdle()

        assertEquals(1, woken)
        job.cancel()
    }

    @Test
    fun `without a signed-in user the card subscribes to nothing`() = runTest {
        coEvery { auth.getUserId() } returns null

        assertTrue(repository.changes().toList().isEmpty())
        verify(exactly = 0) { mercure.subscribe(any()) }
    }
}

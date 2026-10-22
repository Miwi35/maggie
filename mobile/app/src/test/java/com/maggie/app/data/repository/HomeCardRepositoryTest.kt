package com.maggie.app.data.repository

import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureEvent
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.mercure.MercureTopics
import com.maggie.app.data.model.HomeDay
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a card of the Accueil reads and what wakes it (MAG-560). */
class HomeCardRepositoryTest {
    private val api = mockk<MaggieApiService>()
    private val mercure = mockk<MercureService>()
    private val auth = mockk<AuthRepository> { coEvery { getUserId() } returns "01HUSER" }
    private val repository = HomeCardRepository(api, mercure, auth)

    @Test
    fun `a card reads its module for the day, as the API spells it`() = runTest {
        val answer = buildJsonObject { put("count", 3) }
        coEvery { api.getHomeCard("tasks", "tomorrow") } returns answer

        val state = repository.state("tasks", HomeDay.TOMORROW).getOrThrow()

        assertEquals(answer, state)
        coVerify(exactly = 1) { api.getHomeCard("tasks", "tomorrow") }
    }

    @Test
    fun `a failing read is a failure, not a crash`() = runTest {
        coEvery { api.getHomeCard(any(), any()) } throws RuntimeException("503")

        assertTrue(repository.state("agenda", HomeDay.TODAY).isFailure)
    }

    @Test
    fun `the topic of a card is the one the API publishes, with no api segment and no id`() {
        assertEquals("/users/01HUSER/home/tasks", MercureTopics.homeScoped("01HUSER", "tasks"))
    }

    @Test
    fun `a change announced on the card's topic wakes the card once, bursts merged`() = runTest {
        every { mercure.subscribe("/users/01HUSER/home/tasks") } returns
            flowOf(MercureEvent(id = "1"), MercureEvent(id = "2"))

        val changes = repository.changes("tasks").toList()

        assertEquals(1, changes.size)
        verify(exactly = 1) { mercure.subscribe("/users/01HUSER/home/tasks") }
    }

    @Test
    fun `without a signed-in user the card subscribes to nothing`() = runTest {
        coEvery { auth.getUserId() } returns null

        assertTrue(repository.changes("tasks").toList().isEmpty())
        verify(exactly = 0) { mercure.subscribe(any()) }
    }
}

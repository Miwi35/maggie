package com.maggie.app.data.repository

import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.model.ActivityOrigin
import com.maggie.app.data.model.ActivityStatus
import com.maggie.app.data.model.ActivityStatusRequest
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The activity status reads and writes (MAG-615). */
@OptIn(ExperimentalCoroutinesApi::class)
class ActivityStatusRepositoryTest {
    private val api = mockk<MaggieApiService>(relaxed = true)
    private val origin = ActivityOrigin("meal", "m1")
    private val state: JsonObject = buildJsonObject { put("step", 3) }

    private fun TestScope.repository() =
        ActivityStatusRepository(api, TestScope(StandardTestDispatcher(testScheduler)))

    @Test
    fun `nothing in progress reads as null`() = runTest {
        coEvery { api.getActivityStatus("cuisine") } returns null

        assertNull(repository().get("cuisine").getOrThrow())
    }

    @Test
    fun `a status is read as the API answers it`() = runTest {
        val status = ActivityStatus("cuisine", origin, state, "2026-10-26T10:00:00+00:00", "2026-10-26T10:05:00+00:00")
        coEvery { api.getActivityStatus("cuisine") } returns status

        assertEquals(status, repository().get("cuisine").getOrThrow())
    }

    @Test
    fun `a network error is a failure, not an empty status`() = runTest {
        coEvery { api.getActivityStatus("cuisine") } throws java.io.IOException("offline")

        assertTrue(repository().get("cuisine").isFailure)
    }

    @Test
    fun `a put sends the origin and the state`() = runTest {
        val repository = repository()

        repository.put("cuisine", origin, state)
        advanceUntilIdle()

        coVerify(exactly = 1) { api.putActivityStatus("cuisine", ActivityStatusRequest(origin, state)) }
    }

    @Test
    fun `a delete ends the status`() = runTest {
        val repository = repository()

        repository.delete("cuisine")
        advanceUntilIdle()

        coVerify(exactly = 1) { api.deleteActivityStatus("cuisine") }
    }

    @Test
    fun `a read waits for the writes handed over before it`() = runTest {
        val repository = repository()
        coEvery { api.getActivityStatus("cuisine") } returns null

        repository.put("cuisine", origin, state)
        repository.get("cuisine")

        coVerifyOrder {
            api.putActivityStatus("cuisine", any())
            api.getActivityStatus("cuisine")
        }
    }
}

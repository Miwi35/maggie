package com.maggie.app.data.repository

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class GroceryTickQueueTest {

    private val repository = mockk<GroceryListRepository>()

    @Test
    fun `a tick is sent and leaves the queue`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        coEvery { repository.checkItem("a", true) } returns Result.success(Unit)
        val queue = GroceryTickQueue(repository, CoroutineScope(dispatcher))

        queue.tick("a", true)
        assertEquals(mapOf("a" to true), queue.pending.value)
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.checkItem("a", true) }
        assertTrue(queue.pending.value.isEmpty())
    }

    @Test
    fun `the last state asked of an article is the one that stays`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        coEvery { repository.checkItem(any(), any()) } returns Result.failure(IOException("offline"))
        val queue = GroceryTickQueue(repository, CoroutineScope(dispatcher))

        queue.tick("a", true)
        queue.tick("a", false)
        advanceUntilIdle()

        assertEquals(mapOf("a" to false), queue.pending.value)
    }

    @Test
    fun `a missing mark is sent, the last one asked wins, and it leaves the queue`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        coEvery { repository.markMissing("a", any()) } returns Result.failure(IOException("offline"))
        val queue = GroceryTickQueue(repository, CoroutineScope(dispatcher))

        queue.markMissing("a", true)
        queue.markMissing("a", false)
        advanceUntilIdle()
        assertEquals(mapOf("a" to false), queue.pendingMissing.value)

        coEvery { repository.markMissing("a", false) } returns Result.success(Unit)
        queue.flush()

        coVerify(exactly = 0) { repository.checkItem(any(), any()) }
        assertTrue(queue.pendingMissing.value.isEmpty())
    }
}

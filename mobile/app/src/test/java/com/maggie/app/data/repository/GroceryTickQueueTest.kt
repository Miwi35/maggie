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

    @Test
    fun `offline, an untick goes before a missing mark and a tick after it`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val sent = mutableListOf<String>()
        coEvery { repository.checkItem(any(), any()) } answers { sent += "check:${firstArg<String>()}:${secondArg<Boolean>()}"; Result.success(Unit) }
        coEvery { repository.markMissing(any(), any()) } answers { sent += "missing:${firstArg<String>()}"; Result.success(Unit) }
        val queue = GroceryTickQueue(repository, CoroutineScope(dispatcher))

        // Marked in its store then ticked in the fallback; another one unticked then marked.
        queue.markMissing("a", true)
        queue.tick("a", true)
        queue.tick("b", false)
        queue.markMissing("b", true)
        queue.flush()

        assertEquals(listOf("check:a:false", "check:b:false", "missing:a", "missing:b", "check:a:true"), sent)
    }

    @Test
    fun `an article unticked, marked missing then ticked again is unticked before its mark`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val sent = mutableListOf<String>()
        coEvery { repository.checkItem(any(), any()) } answers { sent += "check:${secondArg<Boolean>()}"; Result.success(Unit) }
        coEvery { repository.markMissing(any(), any()) } answers { sent += "missing"; Result.success(Unit) }
        val queue = GroceryTickQueue(repository, CoroutineScope(dispatcher))

        queue.tick("a", false)
        queue.markMissing("a", true)
        queue.tick("a", true)
        queue.flush()

        assertEquals(listOf("check:false", "missing", "check:true"), sent)
        assertTrue(queue.pending.value.isEmpty())
    }

    @Test
    fun `the tick of an article waits while its missing mark is not received`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        coEvery { repository.checkItem(any(), any()) } returns Result.success(Unit)
        coEvery { repository.markMissing("a", true) } returns Result.failure(IOException("offline"))
        val queue = GroceryTickQueue(repository, CoroutineScope(dispatcher))

        queue.markMissing("a", true)
        queue.tick("a", true)
        queue.flush()

        coVerify(exactly = 0) { repository.checkItem("a", true) }
        assertEquals(mapOf("a" to true), queue.pending.value)
    }
}

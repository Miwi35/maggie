package com.maggie.app.data.repository

import com.maggie.app.data.model.ActivityOrigin
import com.maggie.app.data.model.ActivityStatusRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The coalescing writer of the activity status (MAG-615). */
@OptIn(ExperimentalCoroutinesApi::class)
class ActivityStatusWriterTest {

    private fun request(step: Int) =
        ActivityStatusRequest(ActivityOrigin("meal", "m1"), buildJsonObject { put("step", step) })

    private class Recorder {
        val sent = mutableListOf<ActivityWrite>()
        var inFlight = 0
        var maxInFlight = 0
        var gate: CompletableDeferred<Unit>? = null
        var failure: Exception? = null

        suspend fun send(write: ActivityWrite) {
            inFlight++
            maxInFlight = maxOf(maxInFlight, inFlight)
            try {
                gate?.await()
                sent += write
                failure?.let { throw it }
            } finally {
                inFlight--
            }
        }
    }

    private fun TestScope.writer(recorder: Recorder) =
        ActivityStatusWriter(TestScope(StandardTestDispatcher(testScheduler)), recorder::send)

    @Test
    fun `a change is sent`() = runTest {
        val recorder = Recorder()
        val writer = writer(recorder)

        writer.put(request(1))
        advanceUntilIdle()

        assertEquals(listOf<ActivityWrite>(ActivityWrite.Put(request(1))), recorder.sent)
    }

    @Test
    fun `changes made while one is in flight collapse into the last one, never two in flight`() = runTest {
        val recorder = Recorder().apply { gate = CompletableDeferred() }
        val writer = writer(recorder)

        writer.put(request(1))
        runCurrent()
        writer.put(request(2))
        writer.put(request(3))
        writer.put(request(4))
        recorder.gate!!.complete(Unit)
        advanceUntilIdle()

        assertEquals(
            listOf<ActivityWrite>(ActivityWrite.Put(request(1)), ActivityWrite.Put(request(4))),
            recorder.sent,
        )
        assertEquals(1, recorder.maxInFlight)
    }

    @Test
    fun `a delete drops the put still waiting and goes after the one in flight`() = runTest {
        val recorder = Recorder().apply { gate = CompletableDeferred() }
        val writer = writer(recorder)

        writer.put(request(1))
        runCurrent()
        writer.put(request(2))
        writer.delete()
        recorder.gate!!.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf<ActivityWrite>(ActivityWrite.Put(request(1)), ActivityWrite.Delete), recorder.sent)
    }

    @Test
    fun `a failed request is ignored and the next change goes out`() = runTest {
        val recorder = Recorder().apply { failure = java.io.IOException("offline") }
        val writer = writer(recorder)

        writer.put(request(1))
        advanceUntilIdle()
        recorder.failure = null
        writer.put(request(2))
        advanceUntilIdle()

        assertEquals(
            listOf<ActivityWrite>(ActivityWrite.Put(request(1)), ActivityWrite.Put(request(2))),
            recorder.sent,
        )
    }

    @Test
    fun `idle returns once the writes handed over were sent`() = runTest {
        val recorder = Recorder().apply { gate = CompletableDeferred() }
        val writer = writer(recorder)
        writer.put(request(1))
        runCurrent()
        var idle = false

        val waiter = launch { writer.idle(); idle = true }
        runCurrent()
        assertTrue(!idle)
        recorder.gate!!.complete(Unit)
        advanceUntilIdle()

        assertTrue(idle)
        waiter.join()
    }
}

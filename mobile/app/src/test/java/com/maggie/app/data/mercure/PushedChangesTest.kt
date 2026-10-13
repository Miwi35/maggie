package com.maggie.app.data.mercure

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PushedChangesTest {

    private fun push(data: String) = MercureEvent(data = data)

    @Test
    fun `reads the id of each resource and whether it is deleted`() {
        val changes = PushedChanges.of(
            listOf(
                push("""{"@id":"/api/events/01A","summary":"RDV"}"""),
                push("""{"@id":"/api/events/01B","deleted":true}"""),
            ),
        )

        assertEquals(mapOf("01A" to false, "01B" to true), changes)
    }

    @Test
    fun `the last word on an id wins`() {
        val changes = PushedChanges.of(
            listOf(
                push("""{"@id":"/api/events/01A","summary":"RDV"}"""),
                push("""{"@id":"/api/events/01A","deleted":true}"""),
            ),
        )

        assertEquals(mapOf("01A" to true), changes)
    }

    @Test
    fun `a payload without an id or that is not JSON is ignored`() {
        assertEquals(emptyMap<String, Boolean>(), PushedChanges.of(listOf(push(""), push("nope"), push("""{"summary":"x"}"""))))
    }

    @Test
    fun `a stream is batched once it has been quiet for the burst window`() = runTest {
        val batches = flow {
            emit(1); delay(100); emit(2); delay(100); emit(3)
            delay(2_000)
            emit(4)
        }.batched().toList()

        assertEquals(listOf(listOf(1, 2, 3), listOf(4)), batches)
        // The first burst leaves 500 ms after its last item; the last one as the stream ends.
        assertEquals(2_200L, currentTime)
    }
}

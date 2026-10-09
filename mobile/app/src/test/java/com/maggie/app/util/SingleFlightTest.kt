package com.maggie.app.util

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SingleFlightTest {

    @Test
    fun `a call with nothing running starts one run`() = runTest {
        var runs = 0
        val flight = SingleFlight(this) { runs++ }

        flight.run()
        advanceUntilIdle()

        assertEquals(1, runs)
    }

    @Test
    fun `calls made while a run is in progress fold into one follow-up run`() = runTest {
        var runs = 0
        val gate = CompletableDeferred<Unit>()
        val flight = SingleFlight(this) {
            if (runs++ == 0) gate.await()
        }

        flight.run()
        runCurrent()
        repeat(5) { flight.run() }
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(2, runs)
    }

    @Test
    fun `a call after the run has finished starts a new run`() = runTest {
        var runs = 0
        val flight = SingleFlight(this) { runs++ }

        flight.run()
        advanceUntilIdle()
        flight.run()
        advanceUntilIdle()

        assertEquals(2, runs)
    }
}

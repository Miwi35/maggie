package com.maggie.app.ui.screens.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** « Juste 10 minutes » (MAG-499): the countdown holds timestamps, so it cannot drift. */
class FocusTimerTest {

    private val ten = 10 * 60 * 1000L

    @Test
    fun `a new timer reads ten minutes and waits for the start`() {
        val timer = FocusTimer()

        assertEquals(ten, timer.remainingMs(now = 5_000))
        assertEquals(FocusTimerPhase.IDLE, timer.phase(now = 5_000))
        assertFalse(timer.isRunning)
    }

    @Test
    fun `it counts down from the moment it started`() {
        val running = FocusTimer().started(now = 1_000)

        assertEquals(ten - 30_000, running.remainingMs(now = 31_000))
        assertEquals(FocusTimerPhase.RUNNING, running.phase(now = 31_000))
    }

    @Test
    fun `a pause freezes the time left and a resume goes on from there`() {
        val paused = FocusTimer().started(now = 0).paused(now = 120_000)

        assertEquals(ten - 120_000, paused.remainingMs(now = 900_000))
        assertEquals(FocusTimerPhase.PAUSED, paused.phase(now = 900_000))

        val resumed = paused.started(now = 1_000_000)
        assertEquals(ten - 120_000 - 5_000, resumed.remainingMs(now = 1_005_000))
        assertEquals(FocusTimerPhase.RUNNING, resumed.phase(now = 1_005_000))
    }

    @Test
    fun `the time left is read from the clock, so a long absence is counted`() {
        val running = FocusTimer().started(now = 0)

        assertEquals(ten - 7 * 60_000, running.remainingMs(now = 7 * 60_000))
    }

    @Test
    fun `it ends at zero and stays there`() {
        val running = FocusTimer().started(now = 0)

        assertEquals(0, running.remainingMs(now = ten))
        assertEquals(FocusTimerPhase.FINISHED, running.phase(now = ten))
        assertEquals(0, running.remainingMs(now = ten + 60_000))
    }

    @Test
    fun `starting a running timer or pausing an idle one changes nothing`() {
        val running = FocusTimer().started(now = 0)
        val idle = FocusTimer()

        assertSame(running, running.started(now = 50_000))
        assertSame(idle, idle.paused(now = 50_000))
    }

    @Test
    fun `the label rounds up so that 00 00 only shows when it is over`() {
        assertEquals("10:00", timerLabelOf(ten))
        assertEquals("10:00", timerLabelOf(ten - 1))
        assertEquals("09:59", timerLabelOf(ten - 1_000))
        assertEquals("00:01", timerLabelOf(1))
        assertEquals("00:00", timerLabelOf(0))
        assertTrue(timerLabelOf(61_000).startsWith("01:01"))
    }
}

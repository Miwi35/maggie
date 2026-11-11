package com.maggie.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime
import java.time.ZoneId

/** The Santé card's state (MAG-566): what each day shows, composed from the module's resources. */
class HealthHomeStateTest {
    private val zone = ZoneId.of("Europe/Paris")
    private val profile = HealthProfile(stepSource = "health_connect", stepGoal = 8000, needKcal = 1980, fastingProtocol = "16:8")
    private val fasting = FastingStatus(date = "2026-11-11", protocol = "16:8", windowStart = "12:00", windowEnd = "20:00", state = "open")
    private val planned = Workout("W1", "run", "Course", "2026-11-11T18:30:00+01:00", 45, source = "planned")

    private fun reads(
        workouts: List<Workout> = listOf(planned),
        steps: Int? = 6240,
        eaten: Int = 1240,
        profile: HealthProfile? = this.profile,
        fasting: FastingStatus? = this.fasting,
    ) = HealthHomeReads(workouts, DailyEnergy("2026-11-11", steps = steps), DailyBalance("2026-11-11", eatenKcal = eaten, plannedKcal = 1800), profile, fasting)

    private fun state(today: Boolean = true, reads: HealthHomeReads = reads(), now: LocalTime = LocalTime.of(16, 42)) =
        HealthHomeState.from(today, reads, now, zone)

    @Test
    fun `today shows the session, the steps, the balance and the window with its countdown`() {
        val state = state()

        assertEquals(HealthHomeWorkout("W1", "Course", "18:30", 45), state.workout)
        assertEquals(HealthHomeSteps(6240, 8000), state.steps)
        assertEquals(HealthHomeBalance(1240, 1980), state.balance)
        assertEquals(HealthHomeFast("open", "12:00", "20:00", 198, 1f - 198f / 480), state.fast)
    }

    @Test
    fun `tomorrow shows the planned calories and the window without countdown, and no steps`() {
        val state = state(today = false, reads = reads(fasting = fasting.copy(state = null)))

        assertNull(state.steps)
        assertEquals("12:00", state.fast?.windowStart)
        assertEquals(HealthHomeBalance(1800, 1980), state.balance)
        assertNull(state.fast?.minutesToEdge)
    }

    @Test
    fun `without a protocol there is no window line`() {
        assertNull(state(reads = reads(profile = profile.copy(fastingProtocol = null))).fast)
    }

    @Test
    fun `without a step source or a measure there is no steps line`() {
        assertNull(state(reads = reads(profile = profile.copy(stepSource = "none"))).steps)
        assertNull(state(reads = reads(steps = null)).steps)
    }

    @Test
    fun `only a planned session counts, the earliest first`() {
        val watch = planned.copy(id = "W0", source = "watch")
        val later = planned.copy(id = "W2", startedAt = "2026-11-11T20:00:00+01:00")

        assertEquals("W1", state(reads = reads(workouts = listOf(watch, later, planned))).workout?.id)
    }

    @Test
    fun `a day with nothing is empty`() {
        assertTrue(state(reads = HealthHomeReads()).isEmpty)
    }

    @Test
    fun `while the window is still to open the countdown is to its opening`() {
        val fast = state(reads = reads(fasting = fasting.copy(state = "fasting")), now = LocalTime.of(9, 30)).fast

        assertEquals(150, fast?.minutesToEdge)
    }

    @Test
    fun `a closed window has no countdown`() {
        assertNull(state(reads = reads(fasting = fasting.copy(state = "closed"))).fast?.minutesToEdge)
    }

    @Test
    fun `fasting inside the window reads open, counting to the window end and never to tomorrow`() {
        val fast = state(reads = reads(fasting = fasting.copy(state = "fasting")), now = LocalTime.of(12, 2)).fast

        assertEquals("open", fast?.state)
        assertEquals(478, fast?.minutesToEdge)
    }

    @Test
    fun `fasting after the window counts to the next opening`() {
        val fast = state(reads = reads(fasting = fasting.copy(state = "fasting")), now = LocalTime.of(21, 0)).fast

        assertEquals("fasting", fast?.state)
        assertEquals(15 * 60, fast?.minutesToEdge)
    }
}

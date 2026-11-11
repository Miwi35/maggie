package com.maggie.app.ui.screens.health.sport

import com.maggie.app.data.model.WorkoutExercise
import com.maggie.app.data.model.WorkoutSetDone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The gap to the last time on the end of the Sport activity (MAG-781). */
class SportDeltasTest {

    private fun reps(vararg counts: Int, load: Double? = null) = WorkoutExercise(
        exercise = "pompes", name = "Pompes inclinées", sets = counts.size, target = 7, loadKg = load,
        done = counts.map { WorkoutSetDone(reps = it, loadKg = load) },
    )

    @Test
    fun `8 8 7 against 3 x 7 is +2 reps`() {
        assertEquals(SportDelta("+2 rép.", same = false), sportDelta(reps(8, 8, 7), reps(7, 7, 7)))
    }

    @Test
    fun `the same sum is an equal sign`() {
        assertEquals(SportDelta("=", same = true), sportDelta(reps(7, 7, 7), reps(8, 7, 6)))
    }

    @Test
    fun `fewer reps are a minus`() {
        assertEquals(SportDelta("−1 rép.", same = false), sportDelta(reps(7, 7, 6), reps(7, 7, 7)))
    }

    @Test
    fun `seconds and metres say their unit`() {
        val held = { s: Int -> WorkoutExercise(exercise = "plank", name = "Planche", unit = "seconds", sets = 1, target = 30, done = listOf(WorkoutSetDone(seconds = s))) }
        val run = { m: Int -> WorkoutExercise(exercise = "farmer", name = "Marche", unit = "meters", sets = 1, target = 20, done = listOf(WorkoutSetDone(meters = m))) }
        assertEquals("+5 s", sportDelta(held(35), held(30))?.text)
        assertEquals("−10 m", sportDelta(run(20), run(30))?.text)
    }

    @Test
    fun `a changed load gives the load gap, not the reps`() {
        assertEquals(SportDelta("+5 kg", same = false), sportDelta(reps(8, 8, 8, load = 15.0), reps(7, 7, 7, load = 10.0)))
        assertEquals("−2,5 kg", sportDelta(reps(8, load = 7.5), reps(8, load = 10.0))?.text)
    }

    @Test
    fun `a load added to a bodyweight exercise is the gap`() {
        assertEquals("+5 kg", sportDelta(reps(8, load = 5.0), reps(8))?.text)
    }

    @Test
    fun `without a last time there is no gap`() {
        assertNull(sportDelta(reps(8), null))
    }
}

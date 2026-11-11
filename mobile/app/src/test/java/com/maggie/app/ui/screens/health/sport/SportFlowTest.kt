package com.maggie.app.ui.screens.health.sport

import com.maggie.app.data.model.WorkoutExercise
import org.junit.Assert.assertEquals
import org.junit.Test

/** The labels of the Sport activity's moves (MAG-780). */
class SportFlowTest {

    private val exercises = listOf(
        WorkoutExercise(exercise = "pull", name = "Tractions négatives", sets = 3, target = 5, restSeconds = 120),
        WorkoutExercise(exercise = "row", name = "Rowing incliné", sets = 3, target = 8, restSeconds = 90),
        WorkoutExercise(exercise = "plank", name = "Planche", unit = "seconds", sets = 2, target = 30, restSeconds = 60),
    )

    private fun at(exercise: Int, set: Int) = SportState(exerciseIndex = exercise, setIndex = set)

    @Test
    fun `during a set, Ensuite says the rest and the next set, then the next exercise, then the end`() {
        assertEquals("Repos 2:00 · série 3", nextInSet(exercises, at(0, 1)))
        assertEquals("Rowing incliné", nextInSet(exercises, at(0, 2)))
        assertEquals("Fin de la séance", nextInSet(exercises, at(2, 1)))
    }

    @Test
    fun `during a rest, Ensuite says the next set, then the next exercise with its plan`() {
        assertEquals("Série 3 · 5", nextInRest(exercises, at(0, 1)))
        assertEquals("Rowing incliné · 3 × 8", nextInRest(exercises, at(0, 2)))
        assertEquals("Planche · 2 × 30 s", nextInRest(exercises, at(1, 2)))
        assertEquals("Fin de la séance", nextInRest(exercises, at(2, 1)))
    }

    @Test
    fun `the ring's clock, the plan and the lest read as the mockup`() {
        assertEquals("1:22", ringClock(82))
        assertEquals("2:00", ringClock(120))
        assertEquals("0:05", ringClock(5))
        assertEquals("3 × 5", planLabel(exercises[0], null))
        assertEquals("3 × 5 · 10 kg", planLabel(exercises[0], 10.0))
        assertEquals("3 × 5", planLabel(exercises[0], 0.0))
        assertEquals("2 × 30 s", planLabel(exercises[2], null))
        assertEquals("7,5 kg", loadLabel(7.5))
        assertEquals("Repos", ringCaption(SportPhase.REST, true))
        assertEquals("Tenez", ringCaption(SportPhase.HOLD, true))
        assertEquals("Prêt", ringCaption(SportPhase.WORK, false))
    }
}

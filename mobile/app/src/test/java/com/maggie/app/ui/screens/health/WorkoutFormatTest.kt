package com.maggie.app.ui.screens.health

import com.maggie.app.data.model.Workout
import com.maggie.app.data.model.WorkoutExercise
import com.maggie.app.data.model.WorkoutSetDone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class WorkoutFormatTest {

    private val paris = ZoneId.of("Europe/Paris")

    @Test
    fun `a duration is in minutes under an hour, then hours and two-digit minutes`() {
        assertEquals("45 min", formatWorkoutDuration(45))
        assertEquals("1 h 05", formatWorkoutDuration(65))
        assertEquals("2 h 00", formatWorkoutDuration(120))
    }

    @Test
    fun `a distance is in kilometres with one decimal and a comma`() {
        assertEquals("5,4 km", formatWorkoutDistance(5400))
        assertEquals("22,0 km", formatWorkoutDistance(22000))
        assertEquals("0,0 km", formatWorkoutDistance(0))
    }

    @Test
    fun `kcal and heart rate carry their unit`() {
        assertEquals("386 kcal", formatWorkoutKcal(386))
        assertEquals("152 bpm", formatWorkoutHeartRate(152))
    }

    @Test
    fun `a date reads in French whatever the phone's language`() {
        assertEquals("Lun. 19 oct.", formatWorkoutDate(LocalDate.of(2026, 10, 19)))
        assertEquals("Dim. 1 févr.", formatWorkoutDate(LocalDate.of(2026, 2, 1)))
    }

    @Test
    fun `the meta line is the day, the duration and the distance when there is one`() {
        val run = Workout("1", "run", "Course", "2026-10-19T07:10:00+02:00", 32, distanceMeters = 5400)
        val gym = run.copy(id = "2", type = "strength", distanceMeters = null, durationMinutes = 65)
        assertEquals("Lun. 19 oct. · 32 min · 5,4 km", workoutMeta(run, paris))
        assertEquals("Lun. 19 oct. · 1 h 05", workoutMeta(gym, paris))
    }

    @Test
    fun `the day is the one of the zone, not the one of the offset in the text`() {
        val late = Workout("1", "run", "Course", "2026-10-18T23:30:00+00:00", 20)
        assertEquals("Lun. 19 oct. · 20 min", workoutMeta(late, paris))
    }

    @Test
    fun `the distance field is read in metres, comma or dot`() {
        assertEquals(5400, parseDistanceMeters("5,4"))
        assertEquals(5400, parseDistanceMeters("5.4"))
        assertEquals(22000, parseDistanceMeters(" 22 "))
        assertEquals(12, parseDistanceMeters("0,012"))
        assertNull(parseDistanceMeters("abc"))
        assertNull(parseDistanceMeters(""))
    }

    @Test
    fun `metres go back to the field the way they were typed`() {
        assertEquals("5,4", distanceInput(5400))
        assertEquals("22", distanceInput(22000))
        assertEquals("0,5", distanceInput(500))
    }

    private fun exercise(unit: String = "reps", sets: Int = 3, target: Int = 5, loadKg: Double? = null, done: List<WorkoutSetDone> = emptyList()) =
        WorkoutExercise("x", "X", unit = unit, sets = sets, target = target, loadKg = loadKg, done = done)

    @Test
    fun `a prescribed dose is sets times target, with its unit and its load`() {
        assertEquals("3 × 5", formatDose(exercise()))
        assertEquals("3 × 10 · 10 kg", formatDose(exercise(target = 10, loadKg = 10.0)))
        assertEquals("3 × 30 s", formatDose(exercise(unit = "seconds", target = 30)))
        assertEquals("4 × 200 m", formatDose(exercise(unit = "meters", sets = 4, target = 200)))
        assertEquals("3 × 8 · 12,5 kg", formatDose(exercise(target = 8, loadKg = 12.5)))
    }

    @Test
    fun `equal sets done read as one count, with the load they share`() {
        val done = List(3) { WorkoutSetDone(reps = 9, loadKg = 10.0) }
        assertEquals("3 × 9 · 10 kg", formatDose(exercise(target = 10, loadKg = 12.5, done = done)))
    }

    @Test
    fun `equal sets done without a load of their own take the prescribed one, or none`() {
        val done = List(3) { WorkoutSetDone(reps = 9) }
        assertEquals("3 × 9 · 10 kg", formatDose(exercise(target = 10, loadKg = 10.0, done = done)))
        assertEquals("3 × 9", formatDose(exercise(target = 10, done = done)))
        val mixed = listOf(WorkoutSetDone(reps = 9, loadKg = 10.0), WorkoutSetDone(reps = 9, loadKg = 12.0))
        assertEquals("2 × 9 · 11 kg", formatDose(exercise(target = 10, loadKg = 11.0, done = mixed)))
    }

    @Test
    fun `unequal sets done are listed one by one, with their unit and no load`() {
        val reps = listOf(8, 8, 7).map { WorkoutSetDone(reps = it, loadKg = 10.0) }
        assertEquals("8 · 8 · 7", formatDose(exercise(target = 8, loadKg = 10.0, done = reps)))
        val seconds = listOf(30, 30, 25).map { WorkoutSetDone(seconds = it) }
        assertEquals("30 s · 30 s · 25 s", formatDose(exercise(unit = "seconds", target = 30, done = seconds)))
    }
}

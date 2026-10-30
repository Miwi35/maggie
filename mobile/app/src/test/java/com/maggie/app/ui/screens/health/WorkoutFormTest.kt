package com.maggie.app.ui.screens.health

import com.maggie.app.data.model.Workout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** The form's rules (MAG-705): what is required, what is converted, what goes into the request. */
class WorkoutFormTest {

    private val paris = ZoneId.of("Europe/Paris")

    private fun draft(
        title: String = "Course du matin",
        duration: String = "32",
        distance: String = "",
        kcal: String = "",
        heartRate: String = "",
    ) = WorkoutDraft(
        title = title, date = LocalDate.of(2026, 10, 19), time = LocalTime.of(7, 10),
        duration = duration, distance = distance, kcal = kcal, heartRate = heartRate,
    )

    @Test
    fun `a title and a duration are required, the rest is optional`() {
        assertEquals(
            setOf(WorkoutField.TITLE, WorkoutField.DURATION),
            draft(title = " ", duration = "").errors().keys,
        )
        assertTrue(draft().errors().isEmpty())
    }

    @Test
    fun `the distance in km becomes whole metres`() {
        assertEquals(5400, draft(distance = "5,4").toRequest(paris)?.distanceMeters)
        assertEquals(5400, draft(distance = "5.4").toRequest(paris)?.distanceMeters)
        assertNull(draft(distance = "").toRequest(paris)?.distanceMeters)
    }

    @Test
    fun `figures outside what the API takes are refused, in French`() {
        val errors = draft(duration = "1441", distance = "1001", kcal = "20001", heartRate = "10").errors()

        assertEquals(
            setOf(WorkoutField.DURATION, WorkoutField.DISTANCE, WorkoutField.KCAL, WorkoutField.HEART_RATE),
            errors.keys,
        )
        assertEquals("Indiquez une durée de 1 à 1440 minutes.", errors[WorkoutField.DURATION])
        assertNull(draft(duration = "0").toRequest(paris))
        assertNull(draft(distance = "abc").toRequest(paris))
    }

    @Test
    fun `the request carries every field, the start with its offset`() {
        val request = draft(distance = "5,4", kcal = "386", heartRate = "152").copy(
            type = WorkoutType.BIKE, feeling = WorkoutFeeling.GOOD, notes = " Sous la pluie ",
        ).toRequest(paris)

        assertEquals("bike", request?.type)
        assertEquals("Course du matin", request?.title)
        assertEquals("2026-10-19T07:10:00+02:00", request?.startedAt)
        assertEquals(32, request?.durationMinutes)
        assertEquals(386, request?.kcal)
        assertEquals(152, request?.avgHeartRate)
        assertEquals("good", request?.feeling)
        assertEquals("Sous la pluie", request?.notes)
    }

    @Test
    fun `editing a session fills the fields back as they were typed`() {
        val workout = Workout(
            "1", "swim", "Piscine", "2026-10-19T07:10:00+02:00", 45,
            distanceMeters = 1500, kcal = 300, avgHeartRate = 140, feeling = "hard", notes = "Crawl",
        )
        val draft = WorkoutDraft.of(workout, paris)

        assertEquals(WorkoutType.SWIM, draft.type)
        assertEquals(LocalDate.of(2026, 10, 19), draft.date)
        assertEquals(LocalTime.of(7, 10), draft.time)
        assertEquals("1,5", draft.distance)
        assertEquals(WorkoutFeeling.HARD, draft.feeling)
        assertEquals("Crawl", draft.notes)
    }
}

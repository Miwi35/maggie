package com.maggie.app.ui.screens.health

import com.maggie.app.data.model.Workout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** « Cette semaine » (MAG-705): the ISO week of Wednesday 21 October 2026, in Paris. */
class WorkoutWeekTest {

    private val paris = ZoneId.of("Europe/Paris")
    private val today = LocalDate.of(2026, 10, 21)

    private fun workout(id: String, startedAt: String, source: String = "manual") =
        Workout(id, "run", "Séance $id", startedAt, 30, source = source)

    @Test
    fun `the week runs from Monday to Sunday with their letters`() {
        val week = weekOf(emptyList(), today, paris)

        assertEquals((19..25).map { LocalDate.of(2026, 10, it) }, week.days.map { it.date })
        assertEquals(listOf("L", "M", "M", "J", "V", "S", "D"), week.days.map { it.letter })
        assertTrue(week.days.all { it.state == DayState.NONE })
    }

    @Test
    fun `a session done fills its day, a planned one rings it, today is marked`() {
        val week = weekOf(
            listOf(
                workout("a", "2026-10-19T07:10:00+02:00"),
                workout("b", "2026-10-21T18:30:00+02:00", "planned"),
                workout("c", "2026-10-23T18:30:00+02:00", "planned"),
            ),
            today,
            paris,
        )

        assertEquals(DayState.DONE, week.days[0].state)
        assertEquals(DayState.PLANNED, week.days[2].state)
        assertEquals(DayState.PLANNED, week.days[4].state)
        assertEquals(listOf(false, false, true, false, false, false, false), week.days.map { it.isToday })
    }

    @Test
    fun `a day with a session done and one planned is filled`() {
        val week = weekOf(
            listOf(workout("a", "2026-10-21T07:00:00+02:00"), workout("b", "2026-10-21T18:30:00+02:00", "planned")),
            today,
            paris,
        )
        assertEquals(DayState.DONE, week.days[2].state)
    }

    @Test
    fun `the counter counts sessions, not days`() {
        val week = weekOf(
            listOf(
                workout("a", "2026-10-19T07:00:00+02:00"),
                workout("b", "2026-10-19T18:00:00+02:00"),
                workout("c", "2026-10-22T18:30:00+02:00", "planned"),
            ),
            today,
            paris,
        )
        assertEquals(2, week.done)
        assertEquals(1, week.planned)
        assertEquals("2 faites · 1 prévue", week.counter)
        assertEquals("0 faite · 0 prévue", weekOf(emptyList(), today, paris).counter)
    }

    @Test
    fun `sessions of other weeks are left out`() {
        val week = weekOf(
            listOf(workout("before", "2026-10-18T10:00:00+02:00"), workout("after", "2026-10-26T10:00:00+01:00")),
            today,
            paris,
        )
        assertEquals(0, week.done)
        assertTrue(week.days.all { it.state == DayState.NONE })
    }

    @Test
    fun `a session is placed on the day of the local clock`() {
        // 23:30 UTC on Sunday 18 is 01:30 on Monday 19 in Paris.
        val week = weekOf(listOf(workout("late", "2026-10-18T23:30:00+00:00")), today, paris)
        assertEquals(DayState.DONE, week.days[0].state)
        assertEquals(1, week.done)
    }

    @Test
    fun `each day describes itself for a screen reader`() {
        val week = weekOf(
            listOf(workout("a", "2026-10-19T07:10:00+02:00"), workout("b", "2026-10-21T18:30:00+02:00", "planned")),
            today,
            paris,
        )
        assertEquals("L 19 : séance faite", week.days[0].description)
        assertEquals("M 21 : séance prévue", week.days[2].description)
        assertEquals("J 22", week.days[3].description)
        assertFalse(week.days[3].isToday)
    }
}

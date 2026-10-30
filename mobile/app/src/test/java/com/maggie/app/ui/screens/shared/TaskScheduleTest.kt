package com.maggie.app.ui.screens.shared

import com.maggie.app.data.model.Task
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/** The « Programmée » of a task (MAG-718): each change keeps the API's rules. */
class TaskScheduleTest {

    private val friday = LocalDate.of(2026, 10, 30)
    private val thursday = LocalDate.of(2026, 10, 29)
    private val six = LocalTime.of(18, 0)

    @Test
    fun `a first switch to a precise time defaults the reminders to « À l'heure »`() {
        val schedule = TaskSchedule(friday, Task.MOMENT_EVENING).atTime(six)

        assertTrue(schedule.isAtTime)
        assertEquals(listOf(0), schedule.reminders)
    }

    @Test
    fun `changing the time keeps the reminders already set`() {
        val schedule = TaskSchedule(friday, Task.MOMENT_TIME, six, listOf(30, 60)).atTime(LocalTime.of(19, 0))

        assertEquals(listOf(30, 60), schedule.reminders)
        assertEquals("19:00", schedule.scheduledTime)
    }

    @Test
    fun `another moment drops the time and the reminders`() {
        val schedule = TaskSchedule(friday, Task.MOMENT_TIME, six, listOf(0)).atMoment(Task.MOMENT_MORNING)

        assertFalse(schedule.isAtTime)
        assertNull(schedule.scheduledTime)
        assertEquals(emptyList<Int>(), schedule.remindersToApi())
    }

    @Test
    fun `no day clears it all, a new day keeps the moment`() {
        val atSix = TaskSchedule(friday, Task.MOMENT_TIME, six, listOf(0))

        assertEquals(TaskSchedule(), atSix.onDay(null))
        assertEquals(atSix.copy(day = thursday), atSix.onDay(thursday))
    }

    @Test
    fun `the merge-patch carries every key, cleared ones as null or empty`() {
        val cleared = buildJsonObject { TaskSchedule().writeTo(this) }
        assertEquals("""{"scheduledDate":null,"scheduledMoment":null,"scheduledTime":null,"reminders":[]}""", cleared.toString())

        val evening = buildJsonObject { TaskSchedule(friday, Task.MOMENT_EVENING).writeTo(this) }
        assertEquals("""{"scheduledDate":"2026-10-30","scheduledMoment":"evening","scheduledTime":null,"reminders":[]}""", evening.toString())

        val atSix = buildJsonObject { TaskSchedule(friday, Task.MOMENT_TIME, six, listOf(0, 30)).writeTo(this) }
        assertEquals("""{"scheduledDate":"2026-10-30","scheduledMoment":"time","scheduledTime":"18:00","reminders":[0,30]}""", atSix.toString())
    }

    @Test
    fun `the read line says the weekday this week, the date further, then the moment or the time`() {
        assertEquals("vendredi · soir", TaskSchedule(friday, Task.MOMENT_EVENING).readLabel(thursday))
        assertEquals("vendredi · 18:00", TaskSchedule(friday, Task.MOMENT_TIME, six).readLabel(thursday))
        assertEquals("vendredi", TaskSchedule(friday).readLabel(thursday))
        assertEquals("vendredi 30 octobre · après-midi", TaskSchedule(friday, Task.MOMENT_AFTERNOON).readLabel(friday.minusDays(10)))
        assertNull(TaskSchedule().readLabel(thursday))
    }

    @Test
    fun `the form opens on the task's programmation, and drops what the API's rules forbid`() {
        val task = Task(id = "t1", title = "Garage", scheduledDate = "2026-10-30", scheduledMoment = "time", scheduledTime = "18:00", reminders = listOf(0))
        assertEquals(TaskSchedule(friday, Task.MOMENT_TIME, six, listOf(0)), TaskSchedule.of(task))

        // A time without its moment, a moment without its time, an unknown moment: the day stays alone.
        assertEquals(TaskSchedule(friday), TaskSchedule.of(task.copy(scheduledMoment = "time", scheduledTime = null)))
        assertEquals(TaskSchedule(friday), TaskSchedule.of(task.copy(scheduledMoment = "night", scheduledTime = null, reminders = emptyList())))
        assertEquals(TaskSchedule(friday, Task.MOMENT_EVENING), TaskSchedule.of(task.copy(scheduledMoment = "evening")))
        assertEquals(TaskSchedule(), TaskSchedule.of(task.copy(scheduledDate = null)))
        assertEquals(TaskSchedule(), TaskSchedule.of(null))
    }

    @Test
    fun `a task's reminder at its time reads « À l'heure », the others as an event's`() {
        assertEquals("À l'heure", taskReminderLabel(0))
        assertEquals("30 minutes avant", taskReminderLabel(30))
        assertEquals("1 heure avant", taskReminderLabel(60))
    }
}

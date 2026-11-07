package com.maggie.app.ui.screens.fullcalendar

import com.maggie.app.data.model.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** A scheduled task in the agenda (MAG-718): on its scheduled day, at its time when it has one. */
class ScheduledTaskEventTest {

    private val paris = ZoneId.of("Europe/Paris")
    private val task = Task(id = "t1", title = "Appeler le garage", dueDate = "2026-11-06")

    @Test
    fun `a task at a precise time is a timed entry, as long as its estimate`() {
        val event = scheduledTaskEvent(
            task.copy(scheduledDate = "2026-10-31", scheduledMoment = "time", scheduledTime = "18:00", estimatedMinutes = 45),
            paris,
        )!!

        assertFalse(event.allDay)
        // Winter time: Paris is UTC+1 on the 31st.
        assertEquals("2026-10-31T17:00:00Z", event.startAt)
        assertEquals("2026-10-31T17:45:00Z", event.endAt)
        assertEquals("Appeler le garage", event.summary)
        assertEquals(TASKS_FILTER_ID, event.agendaIri)
        assertEquals("t1", event.id)
    }

    @Test
    fun `a task with a moment is an item of its day, named by the moment`() {
        val day = LocalDate.of(2026, 10, 31)
        val evening = scheduledTaskEvent(task.copy(scheduledDate = "2026-10-31", scheduledMoment = "evening"), paris)!!
        val bare = scheduledTaskEvent(task.copy(scheduledDate = "2026-10-31"), paris)!!

        assertTrue(evening.allDay)
        assertEquals(day, evening.startDate)
        assertEquals(day.plusDays(1), evening.endDate)
        assertEquals("Soir : Appeler le garage", evening.summary)
        assertEquals("Matin : Appeler le garage", scheduledTaskEvent(task.copy(scheduledDate = "2026-10-31", scheduledMoment = "morning"), paris)!!.summary)
        assertEquals("Appeler le garage", bare.summary)
    }

    @Test
    fun `a task with no scheduled day is not in the agenda, whatever its due date`() {
        assertNull(scheduledTaskEvent(task, paris))
    }
}

package com.maggie.app.ui.screens.tasks

import com.maggie.app.data.model.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/** The pills of a task of the day (MAG-766), each branch. */
class TodayRowsTest {
    /** A Thursday. */
    private val today = LocalDate.of(2026, 10, 15)

    private fun task(
        scheduledDate: String? = null,
        moment: String? = null,
        time: String? = null,
        dueDate: String? = null,
        urgency: String? = null,
        completedAt: String? = null,
        timed: Boolean = false,
        minutes: Int? = null,
        postponedSince: String? = null,
    ) = Task(
        id = "t",
        title = "Tâche",
        scheduledDate = scheduledDate,
        scheduledMoment = moment,
        scheduledTime = time,
        dueDate = dueDate,
        urgency = urgency,
        completedAt = completedAt,
        timed = timed,
        estimatedMinutes = minutes,
        postponedSince = postponedSince,
    )

    @Test
    fun `planned today at a time says « à 10h00 », before any deadline`() {
        assertEquals(WhenChip(WhenKind.AT, "à 10:00"), TodayRows.whenOf(task("2026-10-15", Task.MOMENT_TIME, "10:00", dueDate = "2026-10-15"), today))
    }

    @Test
    fun `planned today at a moment says it`() {
        assertEquals(WhenChip(WhenKind.MOMENT, "ce matin"), TodayRows.whenOf(task("2026-10-15", Task.MOMENT_MORNING), today))
        assertEquals(WhenChip(WhenKind.MOMENT, "cet après-midi"), TodayRows.whenOf(task("2026-10-15", Task.MOMENT_AFTERNOON), today))
        assertEquals(WhenChip(WhenKind.MOMENT, "ce soir"), TodayRows.whenOf(task("2026-10-15", Task.MOMENT_EVENING), today))
    }

    @Test
    fun `a deadline today says « avant ce soir », another day « limite » with its day`() {
        assertEquals(WhenChip(WhenKind.DUE_TODAY, "avant ce soir"), TodayRows.whenOf(task(dueDate = "2026-10-15"), today))
        assertEquals(WhenChip(WhenKind.DUE, "limite ven. 23"), TodayRows.whenOf(task("2026-10-15", dueDate = "2026-10-23"), today))
        assertEquals(WhenChip(WhenKind.DUE, "limite lun. 12"), TodayRows.whenOf(task(dueDate = "2026-10-12"), today))
        assertEquals(WhenChip(WhenKind.DUE, "limite mar. 3 nov."), TodayRows.whenOf(task(dueDate = "2026-11-03"), today))
    }

    @Test
    fun `a moment planned another day gives way to the deadline, and nothing is said without either`() {
        assertEquals(WhenChip(WhenKind.DUE_TODAY, "avant ce soir"), TodayRows.whenOf(task("2026-10-16", Task.MOMENT_EVENING, dueDate = "2026-10-15"), today))
        assertNull(TodayRows.whenOf(task("2026-10-15"), today))
        assertNull(TodayRows.whenOf(task(), today))
    }

    @Test
    fun `a task done has no pill`() {
        val done = task("2026-10-15", Task.MOMENT_TIME, "10:00", urgency = "critical", completedAt = "2026-10-15T07:12:00Z")
        assertNull(TodayRows.whenOf(done, today))
        assertNull(TodayRows.urgencyOf(done))
    }

    @Test
    fun `only critical and high have an urgency pill`() {
        assertEquals(UrgencyChip.CRITICAL, TodayRows.urgencyOf(task(urgency = "critical")))
        assertEquals(UrgencyChip.HIGH, TodayRows.urgencyOf(task(urgency = "high")))
        assertNull(TodayRows.urgencyOf(task(urgency = "normal")))
        assertNull(TodayRows.urgencyOf(task(urgency = "low")))
        assertNull(TodayRows.urgencyOf(task()))
    }

    @Test
    fun `the meta says when it was done, else since when it is put off, else the estimate of a task not timed`() {
        assertEquals("faite à 9 h 12", TodayRows.metaOf(task(completedAt = "2026-10-15T07:12:00Z"), today))
        assertEquals("reportée depuis lundi", TodayRows.metaOf(task(postponedSince = "2026-10-12", minutes = 20), today))
        assertEquals("45 min", TodayRows.metaOf(task(minutes = 45), today))
        assertNull(TodayRows.metaOf(task(minutes = 20, timed = true), today))
        assertNull(TodayRows.metaOf(task(), today))
    }

    @Test
    fun `the timer is only for a timed task not done, at its minutes or the Focus's ten`() {
        assertEquals(20, TodayRows.timerMinutesOf(task(timed = true, minutes = 20)))
        assertEquals(10, TodayRows.timerMinutesOf(task(timed = true)))
        assertNull(TodayRows.timerMinutesOf(task(minutes = 20)))
        assertNull(TodayRows.timerMinutesOf(task(timed = true, minutes = 20, completedAt = "2026-10-15T07:12:00Z")))
    }
}

package com.maggie.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

/** The « Tâches » card composed from the tasks and routines resources (MAG-588), as the API's home answer used to be. */
class HomeTasksComposerTest {

    private fun task(id: String, overdueDays: Int? = null, parent: String? = null, score: Int? = null, dueDate: String? = null, position: String? = null) =
        Task(id = id, title = "Tâche $id", overdueDays = overdueDays, parent = parent, orderScore = score, dueDate = dueDate, position = position)

    private fun routine(id: String, time: String, checked: Boolean = false) =
        DayRoutine(id = id, title = "Routine $id", anchor = "time", time = time, checked = checked)

    private fun day(vararg moments: RoutineMoment) = RoutineDayView(date = "2026-10-23", moments = moments.toList())

    private fun moment(time: String, vararg routines: DayRoutine) = RoutineMoment(anchor = "time", time = time, routines = routines.toList())

    private val noRoutines = RoutineDayView(date = "2026-10-23")

    @Test
    fun `today lists the open tasks then the done ones, five rows at most`() {
        val open = (1..4).map { task("o$it") }
        val done = (1..3).map { task("d$it") }

        val card = HomeTasksComposer.today(open, done, noRoutines, LocalTime.NOON)

        assertEquals(listOf("o1", "o2", "d1", "d2", "d3"), card.tasks.map { it.id })
        assertEquals(listOf(false, false, true, true, true), card.tasks.map { it.done })
        assertEquals(3, card.done)
        assertEquals(5, card.total)
        assertEquals(2, card.more)
        assertTrue(card.editable)
        assertEquals("today", card.day)
        assertNull(card.saga)
    }

    @Test
    fun `only the five last done tasks are kept, and they leave no room for open ones`() {
        val card = HomeTasksComposer.today(listOf(task("o1")), (1..7).map { task("d$it") }, noRoutines, LocalTime.NOON)

        assertEquals((1..5).map { "d$it" }, card.tasks.map { it.id })
        assertEquals(5, card.done)
        assertEquals(5, card.total)
        assertEquals(1, card.more)
    }

    @Test
    fun `with nothing done the open tasks fill the card and the rest is more`() {
        val card = HomeTasksComposer.today((1..8).map { task("o$it") }, emptyList(), noRoutines, LocalTime.NOON)

        assertEquals(5, card.tasks.size)
        assertEquals(0, card.done)
        assertEquals(5, card.total)
        assertEquals(3, card.more)
    }

    @Test
    fun `late counts every open task, listed or not, and a done row is never late`() {
        val open = listOf(task("o1", overdueDays = 2), task("o2"), task("o3", overdueDays = 0)) + (4..7).map { task("o$it", overdueDays = 1) }
        val done = listOf(task("d1", overdueDays = 9))

        val card = HomeTasksComposer.today(open, done, noRoutines, LocalTime.NOON)

        assertEquals(5, card.overdue)
        assertEquals(2, card.tasks.first { it.id == "o1" }.overdueDays)
        assertNull(card.tasks.first { it.id == "d1" }.overdueDays)
    }

    @Test
    fun `a subtask carries the id of its task, the last segment of the IRI`() {
        val card = HomeTasksComposer.today(listOf(task("a"), task("a1", parent = "/api/tasks/a")), emptyList(), noRoutines, LocalTime.NOON)

        assertNull(card.tasks[0].parentId)
        assertEquals("a", card.tasks[1].parentId)
        assertTrue(card.tasks[1].isSubtask)
    }

    @Test
    fun `nothing at all is an empty card`() {
        assertTrue(HomeTasksComposer.today(emptyList(), emptyList(), noRoutines, LocalTime.NOON).isEmpty)
        assertTrue(HomeTasksComposer.tomorrow(emptyList(), noRoutines).isEmpty)
    }

    @Test
    fun `today shows the routines of the last moment whose hour has passed`() {
        val view = day(
            moment("07:30", routine("r1", "07:30")),
            moment("12:30", routine("r2", "12:30", checked = true), routine("r3", "12:30")),
            moment("20:00", routine("r4", "20:00")),
        )

        val card = HomeTasksComposer.today(emptyList(), emptyList(), view, LocalTime.of(14, 0))

        assertEquals(listOf("r2", "r3"), card.routines.map { it.id })
        assertEquals(listOf(true, false), card.routines.map { it.checked })
        assertEquals(HomeRoutine("r2", "Routine r2", anchor = "time", time = "12:30", checked = true), card.routines.first())
    }

    @Test
    fun `the moment starts at its hour, sharp`() {
        val view = day(moment("07:30", routine("r1", "07:30")), moment("12:30", routine("r2", "12:30")))

        assertEquals(listOf("r2"), HomeTasksComposer.today(emptyList(), emptyList(), view, LocalTime.of(12, 30)).routines.map { it.id })
        assertEquals(listOf("r1"), HomeTasksComposer.today(emptyList(), emptyList(), view, LocalTime.of(12, 29)).routines.map { it.id })
    }

    @Test
    fun `before the first moment the first one is the current`() {
        val view = day(moment("07:30", routine("r1", "07:30")), moment("12:30", routine("r2", "12:30")))

        val card = HomeTasksComposer.today(emptyList(), emptyList(), view, LocalTime.of(5, 0))

        assertEquals(listOf("r1"), card.routines.map { it.id })
    }

    @Test
    fun `a moment whose hour cannot be read is left out`() {
        val view = day(moment("??", routine("r0", "??")), moment("12:30", routine("r2", "12:30")))

        assertEquals(listOf("r2"), HomeTasksComposer.today(emptyList(), emptyList(), view, LocalTime.of(23, 0)).routines.map { it.id })
    }

    @Test
    fun `tomorrow is read only, with every routine due and no done row`() {
        val view = day(moment("07:30", routine("r1", "07:30")), moment("20:00", routine("r4", "20:00")))

        val card = HomeTasksComposer.tomorrow(listOf(task("t1")), view)

        assertFalse(card.editable)
        assertEquals("tomorrow", card.day)
        assertEquals(0, card.done)
        assertEquals(1, card.total)
        assertEquals(listOf("r1", "r4"), card.routines.map { it.id })
    }

    @Test
    fun `tomorrow sorts the tasks by score, then the due day, the position and the id`() {
        val open = listOf(
            task("low", score = 3, dueDate = "2026-10-24", position = "2"),
            task("none", dueDate = "2026-10-24", position = "1"),
            task("high", score = 90, dueDate = "2026-10-24", position = "3"),
            task("tie-b", score = 3, dueDate = "2026-10-24", position = "2"),
            task("tie-first", score = 3, dueDate = "2026-10-24", position = "1"),
        )

        val card = HomeTasksComposer.tomorrow(open, noRoutines)

        assertEquals(listOf("high", "tie-first", "low", "tie-b", "none"), card.tasks.map { it.id })
    }

    @Test
    fun `tomorrow caps at five rows and counts the rest as more`() {
        val card = HomeTasksComposer.tomorrow((1..7).map { task("t$it", position = "$it") }, noRoutines)

        assertEquals(5, card.tasks.size)
        assertEquals(5, card.total)
        assertEquals(2, card.more)
        assertTrue(card.tasks.none { it.done })
    }
}

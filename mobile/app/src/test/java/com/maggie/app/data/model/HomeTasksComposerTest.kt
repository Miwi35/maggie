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

    private fun routine(id: String, time: String, checked: Boolean = false, anchor: String = "time") =
        DayRoutine(id = id, title = "Routine $id", anchor = anchor, time = time, checked = checked)

    private fun day(vararg moments: RoutineMoment) = RoutineDayView(date = "2026-10-23", moments = moments.toList())

    private fun moment(anchor: String, time: String, vararg routines: DayRoutine) =
        RoutineMoment(anchor = anchor, time = time, routines = routines.toList())

    /** The settings' day moments: wake 07:30, afterBreakfast 08:30, afterLunch 12:30, afterDinner 20:00. */
    private val dayMoments = mapOf("wake" to "07:30", "afterBreakfast" to "08:30", "afterLunch" to "12:30", "afterDinner" to "20:00")

    private fun today(view: RoutineDayView, now: LocalTime, moments: Map<String, String> = dayMoments) =
        HomeTasksComposer.today(emptyList(), emptyList(), view, moments, now)

    private val noRoutines = RoutineDayView(date = "2026-10-23")

    @Test
    fun `today lists the open tasks then the done ones, five rows at most`() {
        val open = (1..4).map { task("o$it") }
        val done = (1..3).map { task("d$it") }

        val card = HomeTasksComposer.today(open, done, noRoutines, dayMoments, LocalTime.NOON)

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
        val card = HomeTasksComposer.today(listOf(task("o1")), (1..7).map { task("d$it") }, noRoutines, dayMoments, LocalTime.NOON)

        assertEquals((1..5).map { "d$it" }, card.tasks.map { it.id })
        assertEquals(5, card.done)
        assertEquals(5, card.total)
        assertEquals(1, card.more)
    }

    @Test
    fun `with nothing done the open tasks fill the card and the rest is more`() {
        val card = HomeTasksComposer.today((1..8).map { task("o$it") }, emptyList(), noRoutines, dayMoments, LocalTime.NOON)

        assertEquals(5, card.tasks.size)
        assertEquals(0, card.done)
        assertEquals(5, card.total)
        assertEquals(3, card.more)
    }

    @Test
    fun `late counts every open task, listed or not, and a done row is never late`() {
        val open = listOf(task("o1", overdueDays = 2), task("o2"), task("o3", overdueDays = 0)) + (4..7).map { task("o$it", overdueDays = 1) }
        val done = listOf(task("d1", overdueDays = 9))

        val card = HomeTasksComposer.today(open, done, noRoutines, dayMoments, LocalTime.NOON)

        assertEquals(5, card.overdue)
        assertEquals(2, card.tasks.first { it.id == "o1" }.overdueDays)
        assertNull(card.tasks.first { it.id == "d1" }.overdueDays)
    }

    @Test
    fun `a subtask carries the id of its task, the last segment of the IRI`() {
        val card = HomeTasksComposer.today(listOf(task("a"), task("a1", parent = "/api/tasks/a")), emptyList(), noRoutines, dayMoments, LocalTime.NOON)

        assertNull(card.tasks[0].parentId)
        assertEquals("a", card.tasks[1].parentId)
        assertTrue(card.tasks[1].isSubtask)
    }

    @Test
    fun `nothing at all is an empty card`() {
        assertTrue(HomeTasksComposer.today(emptyList(), emptyList(), noRoutines, dayMoments, LocalTime.NOON).isEmpty)
        assertTrue(HomeTasksComposer.tomorrow(emptyList(), noRoutines).isEmpty)
    }

    @Test
    fun `today shows the routines of the last day moment whose hour has passed`() {
        val view = day(
            moment("wake", "07:30", routine("r1", "07:30", anchor = "wake")),
            moment("afterLunch", "12:30", routine("r2", "12:30", checked = true, anchor = "afterLunch"), routine("r3", "12:30", anchor = "afterLunch")),
            moment("afterDinner", "20:00", routine("r4", "20:00", anchor = "afterDinner")),
        )

        val card = today(view, LocalTime.of(14, 0))

        assertEquals(listOf("r2", "r3"), card.routines.map { it.id })
        assertEquals(listOf(true, false), card.routines.map { it.checked })
        assertEquals(HomeRoutine("r2", "Routine r2", anchor = "afterLunch", time = "12:30", checked = true), card.routines.first())
    }

    @Test
    fun `the current moment is picked among all the day moments, not only those with a routine due`() {
        // Only the wake moment has a routine due; at 10:00 the current moment is afterBreakfast, so nothing shows.
        val view = day(moment("wake", "07:30", routine("r1", "07:30", anchor = "wake")))

        assertTrue(today(view, LocalTime.of(10, 0)).routines.isEmpty())
        assertEquals(listOf("r1"), today(view, LocalTime.of(8, 0)).routines.map { it.id })
    }

    @Test
    fun `the moment starts at its hour, sharp`() {
        val view = day(
            moment("wake", "07:30", routine("r1", "07:30", anchor = "wake")),
            moment("afterLunch", "12:30", routine("r2", "12:30", anchor = "afterLunch")),
        )

        assertEquals(listOf("r2"), today(view, LocalTime.of(12, 30)).routines.map { it.id })
        assertTrue(today(view, LocalTime.of(12, 29)).routines.isEmpty())
    }

    @Test
    fun `before the first day moment the first one is the current`() {
        val view = day(
            moment("wake", "07:30", routine("r1", "07:30", anchor = "wake")),
            moment("afterLunch", "12:30", routine("r2", "12:30", anchor = "afterLunch")),
        )

        assertEquals(listOf("r1"), today(view, LocalTime.of(5, 0)).routines.map { it.id })
    }

    @Test
    fun `the day moments are ordered by their hour, whatever the order of the settings`() {
        val shuffled = mapOf("afterDinner" to "20:00", "wake" to "07:30", "afterLunch" to "12:30")
        val view = day(
            moment("wake", "07:30", routine("r1", "07:30", anchor = "wake")),
            moment("afterDinner", "20:00", routine("r4", "20:00", anchor = "afterDinner")),
        )

        assertEquals("afterLunch", HomeTasksComposer.currentAnchor(shuffled, LocalTime.of(13, 0)))
        assertEquals(listOf("r4"), today(view, LocalTime.of(21, 0), shuffled).routines.map { it.id })
        assertEquals("wake", HomeTasksComposer.currentAnchor(shuffled, LocalTime.of(3, 0)))
    }

    @Test
    fun `a group anchored on a time of its own never shows on today`() {
        val view = day(
            moment("time", "10:00", routine("r1", "10:00")),
            moment("afterBreakfast", "08:30", routine("r2", "08:30", anchor = "afterBreakfast")),
        )

        assertEquals(listOf("r2"), today(view, LocalTime.of(10, 30)).routines.map { it.id })
    }

    @Test
    fun `without day moments there is no routine today`() {
        val view = day(moment("wake", "07:30", routine("r1", "07:30", anchor = "wake")))

        assertTrue(today(view, LocalTime.of(10, 0), emptyMap()).routines.isEmpty())
    }

    @Test
    fun `tomorrow is read only, with every routine due and no done row`() {
        val view = day(
            moment("wake", "07:30", routine("r1", "07:30", anchor = "wake")),
            moment("time", "10:00", routine("r5", "10:00")),
            moment("afterDinner", "20:00", routine("r4", "20:00", anchor = "afterDinner")),
        )

        val card = HomeTasksComposer.tomorrow(listOf(task("t1")), view)

        assertFalse(card.editable)
        assertEquals("tomorrow", card.day)
        assertEquals(0, card.done)
        assertEquals(1, card.total)
        assertEquals(listOf("r1", "r5", "r4"), card.routines.map { it.id })
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

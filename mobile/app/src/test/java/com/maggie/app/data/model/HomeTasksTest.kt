package com.maggie.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** How a tick moves the « Tâches » card of the Accueil before the API has answered (MAG-561). */
class HomeTasksTest {

    private val card = HomeTasks(
        tasks = listOf(
            HomeTask("t1", "Appeler le garage", overdueDays = 2),
            HomeTask("t2", "Réserver le train"),
            HomeTask("t3", "Payer la cantine", done = true),
        ),
        done = 1,
        total = 3,
        overdue = 1,
        routines = listOf(HomeRoutine("r1", "Eau"), HomeRoutine("r2", "Vitamines", checked = true)),
    )

    @Test
    fun `a row with a parent is a subtask`() {
        assertTrue(HomeTask("a", "Choisir", parentId = "p").isSubtask)
        assertFalse(HomeTask("p", "Réserver").isSubtask)
    }

    @Test
    fun `a card without saga, tasks, routines or more is empty`() {
        assertNull(HomeTasks().saga)
        assertTrue(HomeTasks().isEmpty)
    }

    @Test
    fun `ticking a late task strikes it, moves the ring and stops counting it late`() {
        val ticked = card.withTaskDone("t1", true)

        assertTrue(ticked.tasks.first { it.id == "t1" }.done)
        assertEquals(2, ticked.done)
        assertEquals(3, ticked.total)
        assertEquals(0, ticked.overdue)
    }

    @Test
    fun `ticking a task that is not late leaves the late count alone`() {
        assertEquals(1, card.withTaskDone("t2", true).overdue)
    }

    @Test
    fun `unticking a done task takes the ring back`() {
        val unticked = card.withTaskDone("t3", false)

        assertFalse(unticked.tasks.first { it.id == "t3" }.done)
        assertEquals(0, unticked.done)
    }

    @Test
    fun `ticking what is already ticked, or what the card does not list, changes nothing`() {
        assertSame(card, card.withTaskDone("t3", true))
        assertSame(card, card.withTaskDone("nope", true))
    }

    @Test
    fun `a routine is checked and unchecked in place`() {
        assertTrue(card.withRoutineChecked("r1", true).routines.first { it.id == "r1" }.checked)
        assertFalse(card.withRoutineChecked("r2", false).routines.first { it.id == "r2" }.checked)
    }

    @Test
    fun `a card with only more tasks to show is not empty`() {
        assertFalse(HomeTasks(more = 2).isEmpty)
        assertTrue(HomeTasks().isEmpty)
    }
}

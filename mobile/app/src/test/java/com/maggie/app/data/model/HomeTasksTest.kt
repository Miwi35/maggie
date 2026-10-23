package com.maggie.app.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The « Tâches » card of the Accueil as the API sends it, and how a tick moves it before the API has answered (MAG-561). */
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
    fun `the body of the API is read under its own names`() {
        val body = """
            {"module":"tasks","day":"today","editable":true,
             "tasks":[{"id":"01J","title":"Appeler","done":false,"overdueDays":2,"parentId":null},
                      {"id":"01K","title":"Choisir","done":true,"overdueDays":null,"parentId":"01J"}],
             "done":1,"total":2,"more":3,"overdue":1,
             "routines":[{"id":"r","title":"Eau","anchor":"wake","time":"07:30","checked":false}],
             "saga":{"title":"Déménagement","done":2,"total":6}}
        """.trimIndent()

        val read = Json { ignoreUnknownKeys = true }.decodeFromString(HomeTasks.serializer(), body)

        assertEquals(2, read.tasks.first().overdueDays)
        assertTrue(read.tasks.last().isSubtask)
        assertFalse(read.tasks.first().isSubtask)
        assertEquals(3, read.more)
        assertEquals("wake", read.routines.single().anchor)
        assertEquals(HomeSaga("Déménagement", 2, 6), read.saga)
    }

    @Test
    fun `a null saga is no saga`() {
        val read = Json { ignoreUnknownKeys = true }.decodeFromString(HomeTasks.serializer(), """{"saga":null}""")

        assertNull(read.saga)
        assertTrue(read.isEmpty)
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

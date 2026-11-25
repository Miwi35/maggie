package com.maggie.app.ui.screens.tasks

import com.maggie.app.data.model.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class TasksStockTest {
    /** A Thursday: this week ends on Sunday the 18th. */
    private val today = LocalDate.of(2026, 10, 15)

    private fun task(
        id: String,
        due: String? = null,
        planned: String? = null,
        urgency: String? = null,
        parent: String? = null,
        moment: String? = null,
        time: String? = null,
        done: Boolean = false,
    ) = Task(
        id = id,
        title = "Tâche $id",
        dueDate = due,
        scheduledDate = planned,
        scheduledMoment = moment,
        scheduledTime = time,
        urgency = urgency,
        parent = parent?.let(Task::iriOf),
        completedAt = if (done) "2026-10-14T08:00:00Z" else null,
    )

    private fun ids(group: StockGroup) = group.tasks.map { it.id }

    @Test
    fun `a task planned or due by Sunday is this week, later is Plus tard, undated is Sans date`() {
        val groups = TasksStock.group(
            upcoming = listOf(task("a", planned = "2026-10-16"), task("b", due = "2026-10-18"), task("c", due = "2026-10-19")),
            someday = listOf(task("d")),
            today = today,
        )

        assertEquals(listOf(StockGroupKind.THIS_WEEK, StockGroupKind.LATER, StockGroupKind.UNDATED), groups.map { it.kind })
        assertEquals(listOf("a", "b"), ids(groups[0]))
        assertEquals(listOf("c"), ids(groups[1]))
        assertEquals(listOf("d"), ids(groups[2]))
    }

    @Test
    fun `the planned day wins over the due day`() {
        val groups = TasksStock.group(listOf(task("a", planned = "2026-10-16", due = "2026-10-30")), emptyList(), today)

        assertEquals(StockGroupKind.THIS_WEEK, groups.single().kind)
    }

    @Test
    fun `on a Sunday, Monday's task is Plus tard`() {
        val sunday = LocalDate.of(2026, 10, 18)
        val groups = TasksStock.group(listOf(task("a", due = "2026-10-18"), task("b", due = "2026-10-19")), emptyList(), sunday)

        assertEquals(listOf("a"), ids(groups.first { it.kind == StockGroupKind.THIS_WEEK }))
        assertEquals(listOf("b"), ids(groups.first { it.kind == StockGroupKind.LATER }))
    }

    @Test
    fun `Critique then Haute then the rest, the API order kept between equals`() {
        val groups = TasksStock.group(
            listOf(
                task("n1", due = "2026-10-16", urgency = "normal"),
                task("h1", due = "2026-10-16", urgency = "high"),
                task("n2", due = "2026-10-16", urgency = "low"),
                task("c1", due = "2026-10-16", urgency = "critical"),
                task("h2", due = "2026-10-16", urgency = "high"),
            ),
            emptyList(),
            today,
        )

        assertEquals(listOf("c1", "h1", "h2", "n1", "n2"), ids(groups.single()))
    }

    @Test
    fun `a subtask stays right after its task, in its task's group`() {
        val groups = TasksStock.group(
            listOf(
                task("p", due = "2026-10-16"),
                task("s", due = "2026-10-30", parent = "p"),
                task("h", due = "2026-10-17", urgency = "high"),
            ),
            emptyList(),
            today,
        )

        assertEquals(listOf("h", "p", "s"), ids(groups.single()))
        assertEquals(2, groups.single().count)
    }

    @Test
    fun `an empty group is not listed`() {
        assertEquals(emptyList<StockGroup>(), TasksStock.group(emptyList(), emptyList(), today))
        assertEquals(listOf(StockGroupKind.UNDATED), TasksStock.group(emptyList(), listOf(task("a")), today).map { it.kind })
    }

    @Test
    fun `the when pill says jeu 12 18h30, mar 17 soir, limite mer 11, limite 28 nov`() {
        val monday = LocalDate.of(2026, 10, 5)
        assertEquals("jeu. 8 · 18:30", TasksStock.whenOf(task("a", planned = "2026-10-08", moment = Task.MOMENT_TIME, time = "18:30"), monday)?.label)
        assertEquals("mar. 6 · soir", TasksStock.whenOf(task("b", planned = "2026-10-06", moment = Task.MOMENT_EVENING), monday)?.label)
        assertEquals("limite mer. 7", TasksStock.whenOf(task("c", due = "2026-10-07"), monday)?.label)
        assertEquals("limite 28 nov.", TasksStock.whenOf(task("d", due = "2026-11-28"), monday)?.label)
        assertNull(TasksStock.whenOf(task("e"), monday))
    }

    @Test
    fun `a task done has no when pill`() {
        assertNull(TasksStock.whenOf(task("a", due = "2026-10-16", done = true), today))
    }

    @Test
    fun `doneLabel says the next day in French`() {
        assertEquals("Fait · prochaine le 8 déc.", TasksStock.doneLabel(LocalDate.of(2026, 12, 8)))
    }

    @Test
    fun `doneLabel without a next day says Fait`() {
        assertEquals("Fait", TasksStock.doneLabel(null))
    }
}

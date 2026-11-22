package com.maggie.app.ui.screens.tasks

import com.maggie.app.data.model.Task
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

enum class StockGroupKind(val key: String, val label: String) {
    THIS_WEEK("this_week", "Cette semaine"),
    LATER("later", "Plus tard"),
    UNDATED("undated", "Sans date"),
}

/** A group of the « Tâches » section: its tasks in order, each subtask right after its task. */
data class StockGroup(val kind: StockGroupKind, val tasks: List<Task>) {
    /** The number in the header: tasks only, a subtask is not counted. */
    val count: Int get() = tasks.count { !it.isSubtask }
}

/** The « Tâches » section (MAG-934), pure: « À venir » and « Un jour » grouped by week, and what each pill says. */
object TasksStock {
    private val day = DateTimeFormatter.ofPattern("EEE d", Locale.FRENCH)
    private val dayOtherMonth = DateTimeFormatter.ofPattern("d MMM", Locale.FRENCH)

    /**
     * « Cette semaine » holds what is planned (else due) up to this Sunday, « Plus tard » what is after, « Sans date »
     * the « Un jour » tasks. In a group, Critique then Haute then the rest, the API's order kept between equals.
     */
    fun group(upcoming: List<Task>, someday: List<Task>, today: LocalDate): List<StockGroup> {
        val sunday = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
        val byKind = HashMap<StockGroupKind, MutableList<List<Task>>>()
        for (block in blocksOf(upcoming)) {
            val date = block.first().let { it.scheduledDay ?: it.dueDay }
            val kind = when {
                date == null -> StockGroupKind.UNDATED
                date <= sunday -> StockGroupKind.THIS_WEEK
                else -> StockGroupKind.LATER
            }
            byKind.getOrPut(kind) { mutableListOf() }.add(block)
        }
        for (block in blocksOf(someday)) byKind.getOrPut(StockGroupKind.UNDATED) { mutableListOf() }.add(block)
        return StockGroupKind.entries.mapNotNull { kind ->
            val blocks = byKind[kind]?.sortedBy { rankOf(it.first()) } ?: return@mapNotNull null
            StockGroup(kind, blocks.flatten())
        }
    }

    private fun rankOf(task: Task): Int = when (task.urgency) {
        "critical" -> 0
        "high" -> 1
        else -> 2
    }

    /** A task and the subtasks that follow it. */
    private fun blocksOf(tasks: List<Task>): List<List<Task>> {
        val blocks = mutableListOf<MutableList<Task>>()
        for (task in tasks) {
            if (task.isSubtask && blocks.isNotEmpty()) blocks.last().add(task) else blocks.add(mutableListOf(task))
        }
        return blocks
    }

    /**
     * « jeu. 12 · 18:30 », « mar. 17 · soir » for a planned task, « limite mer. 11 » for a deadline; another month
     * than today's says « 28 nov. ». Nothing for a task done.
     */
    fun whenOf(task: Task, today: LocalDate): WhenChip? {
        if (task.isDone) return null
        task.scheduledDay?.let { planned ->
            val label = dayLabel(planned, today)
            task.scheduledAt?.let { return WhenChip(WhenKind.AT, "$label · $it") }
            momentLabel(task.scheduledMoment)?.let { return WhenChip(WhenKind.MOMENT, "$label · $it") }
            return WhenChip(WhenKind.MOMENT, label)
        }
        val due = task.dueDay ?: return null
        return WhenChip(WhenKind.DUE, "limite " + dayLabel(due, today))
    }

    private fun dayLabel(date: LocalDate, today: LocalDate): String =
        date.format(if (date.month == today.month && date.year == today.year) day else dayOtherMonth)

    private fun momentLabel(moment: String?): String? = when (moment) {
        Task.MOMENT_MORNING -> "matin"
        Task.MOMENT_AFTERNOON -> "après-midi"
        Task.MOMENT_EVENING -> "soir"
        else -> null
    }
}

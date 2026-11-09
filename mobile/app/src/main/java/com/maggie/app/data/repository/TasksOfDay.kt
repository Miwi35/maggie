package com.maggie.app.data.repository

import com.maggie.app.data.model.Task
import com.maggie.app.data.model.TaskView
import com.maggie.app.data.model.TaskViewCounts
import com.maggie.app.util.DateRanges
import com.maggie.app.util.rethrowCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * The tasks of the day (MAG-766), what « Aujourd'hui » lists and the Bravo counts: the open ones as the today view
 * orders them, then those done today, as the Accueil's card reads them (MAG-588). A subtask under a task listed is
 * a petit pas, read in the task's sheet and Focus, not a row of its own. [counts] are the today view's, for the tabs.
 */
data class TasksOfDay(val tasks: List<Task>, val counts: TaskViewCounts = TaskViewCounts()) {
    /** The ring, « n sur N ». */
    val done: Int get() = tasks.count { it.isDone }

    val total: Int get() = tasks.size

    companion object {
        /** The tasks done today the day lists: more than a day holds. */
        const val DONE_LIMIT = 50

        fun of(open: List<Task>, doneToday: List<Task>, counts: TaskViewCounts = TaskViewCounts()): TasksOfDay {
            val stillOpen = open.filterNot { it.isDone }
            val openIds = stillOpen.map { it.id }.toSet()
            val listed = stillOpen + doneToday.filter { it.isDone && it.id !in openIds }
            val ids = listed.map { it.id }.toSet()
            val rows = listed.filterNot { task -> task.parent?.substringAfterLast('/')?.let { it in ids } == true }
            return TasksOfDay(rows, counts)
        }
    }
}

/**
 * Reads [TasksOfDay] for [date], in Paris: the today view and, beside it, the tasks done between its midnights. The
 * today view failing fails the read; those done unreadable, the open ones are the day.
 */
suspend fun TaskRepository.tasksOfDay(date: LocalDate): Result<TasksOfDay> = coroutineScope {
    val done = async {
        runCatching {
            getCompletedBetween(
                after = date.atStartOfDay(DateRanges.zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                before = date.plusDays(1).atStartOfDay(DateRanges.zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                limit = TasksOfDay.DONE_LIMIT,
            )
        }.rethrowCancellation().getOrDefault(emptyList())
    }
    getTaskView(TaskView.TODAY).map { response -> TasksOfDay.of(response.member, done.await(), response.counts) }
}

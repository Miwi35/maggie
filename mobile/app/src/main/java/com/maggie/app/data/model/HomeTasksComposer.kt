package com.maggie.app.data.model

import java.time.LocalTime

/**
 * Composes the « Tâches » card of the Accueil from the business resources it reads: the open tasks of the day, the
 * tasks done today, the routines of the day (MAG-588). Pure: the reads are the repository's.
 */
object HomeTasksComposer {
    /** The rows of the card, done ones included. */
    const val SLOTS = 5

    /** Today: the open tasks as the today view orders them, the last tasks done today, the routines of the current moment. */
    fun today(open: List<Task>, done: List<Task>, routines: RoutineDayView, now: LocalTime): HomeTasks {
        val doneRows = done.take(SLOTS)
        val openRows = open.take(SLOTS - doneRows.size)
        return HomeTasks(
            day = HomeDay.TODAY.apiValue,
            editable = true,
            tasks = openRows.map { it.toRow(done = false) } + doneRows.map { it.toRow(done = true) },
            done = doneRows.size,
            total = doneRows.size + openRows.size,
            more = open.size - openRows.size,
            overdue = open.count { (it.overdueDays ?: 0) > 0 },
            routines = currentMoment(routines, now).map { it.toRow() },
        )
    }

    /** Tomorrow: the open tasks due that day, the way the API orders the Tâches, and every routine due; nothing can be ticked. */
    fun tomorrow(open: List<Task>, routines: RoutineDayView): HomeTasks {
        val sorted = open.sortedWith(Task.SCORE_ORDER)
        val rows = sorted.take(SLOTS)
        return HomeTasks(
            day = HomeDay.TOMORROW.apiValue,
            editable = false,
            tasks = rows.map { it.toRow(done = false) },
            done = 0,
            total = rows.size,
            more = sorted.size - rows.size,
            overdue = sorted.count { (it.overdueDays ?: 0) > 0 },
            routines = routines.routines.map { it.toRow() },
        )
    }

    /** The routines of the last moment whose hour has passed at [now], else of the first moment. */
    internal fun currentMoment(day: RoutineDayView, now: LocalTime): List<DayRoutine> {
        val timed = day.moments.mapNotNull { moment -> moment.timeOfDay?.let { it to moment } }
        if (timed.isEmpty()) return emptyList()
        val current = timed.map { it.first }.filter { it <= now }.maxOrNull() ?: timed.minOf { it.first }
        return timed.filter { it.first == current }.flatMap { it.second.routines }
    }

    private fun Task.toRow(done: Boolean) = HomeTask(
        id = id,
        title = title,
        done = done,
        overdueDays = if (done) null else overdueDays,
        parentId = parent?.substringAfterLast('/')?.takeIf { it.isNotBlank() },
    )

    private fun DayRoutine.toRow() = HomeRoutine(id = id, title = title, anchor = anchor, time = time, checked = checked)
}

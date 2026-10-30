package com.maggie.app.ui.screens.fullcalendar

import com.maggie.app.data.model.ExpandedEvent
import com.maggie.app.data.model.Task
import com.maggie.app.data.model.endDateAfter
import java.time.Duration
import java.time.ZoneId

/** The id of the tasks' [ExpandedEvent.agendaIri]: a scheduled task is no agenda's event (MAG-718). */
const val TASKS_FILTER_ID = "__tasks__"
const val TASKS_COLOR = "#1976D2"

/** How long a task at a precise time lasts on the timeline when it has no estimate. */
private val DEFAULT_TASK_LENGTH: Duration = Duration.ofMinutes(30)

private val MOMENT_PREFIXES = mapOf(
    Task.MOMENT_MORNING to "Matin",
    Task.MOMENT_AFTERNOON to "Après-midi",
    Task.MOMENT_EVENING to "Soir",
)

/**
 * A task with a scheduled day as an entry of the agenda (MAG-718): at its time, as long as its estimate,
 * when the moment is `time`; otherwise an item of the day, « Soir : … » when it has a moment. The id stays
 * the task's, so a touch opens its sheet. A task with no scheduled day is not in the agenda — the deadline
 * is no day it is done on. Null for those.
 */
fun scheduledTaskEvent(task: Task, zone: ZoneId): ExpandedEvent? {
    val day = task.scheduledDay ?: return null
    val time = task.scheduledAt
    if (time != null) {
        val start = day.atTime(time).atZone(zone).toInstant()
        val length = task.estimatedMinutes?.let { Duration.ofMinutes(it.toLong()) } ?: DEFAULT_TASK_LENGTH
        return ExpandedEvent(
            id = task.id,
            summary = task.title,
            startAt = start.toString(),
            endAt = start.plus(length).toString(),
            timeZone = zone.id,
            agendaIri = TASKS_FILTER_ID,
            agendaColor = TASKS_COLOR,
            agendaName = "Tâches",
        )
    }
    val prefix = task.scheduledMoment?.let { MOMENT_PREFIXES[it] }
    return ExpandedEvent(
        id = task.id,
        summary = if (prefix != null) "$prefix : ${task.title}" else task.title,
        allDay = true,
        startDate = day,
        endDate = endDateAfter(day),
        agendaIri = TASKS_FILTER_ID,
        agendaColor = TASKS_COLOR,
        agendaName = "Tâches",
    )
}

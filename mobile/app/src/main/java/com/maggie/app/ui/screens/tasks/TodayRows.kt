package com.maggie.app.ui.screens.tasks

import com.maggie.app.data.model.Task
import com.maggie.app.ui.screens.shared.TaskPostponed
import com.maggie.app.ui.screens.shared.minutesLabel
import com.maggie.app.util.DateRanges
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What the « quand » pill of a row says (MAG-766), and in which colour: the plan of the day, else the deadline. */
enum class WhenKind {
    /** « à 10:00 »: planned today at a precise time. */
    AT,

    /** « ce matin », « cet après-midi », « ce soir »: planned today at a moment. */
    MOMENT,

    /** « avant ce soir », amber: due today. */
    DUE_TODAY,

    /** « limite ven. 13 », grey: due another day. */
    DUE,
}

data class WhenChip(val kind: WhenKind, val label: String)

/** The urgency pill (MAG-766): only « Critique » and « Haute » are drawn, never Normale. */
enum class UrgencyChip(val label: String) {
    CRITICAL("Critique"),
    HIGH("Haute"),
}

/**
 * The rows of « Aujourd'hui » (MAG-766), pure: what each pill of a task says. A task done shows none of them,
 * only « faite à 9 h 12 ».
 */
object TodayRows {
    private val dueDay = DateTimeFormatter.ofPattern("EEE d", Locale.FRENCH)
    private val dueDayOtherMonth = DateTimeFormatter.ofPattern("EEE d MMM", Locale.FRENCH)

    /**
     * Planned today: its time (« à 10:00 ») or its moment. Otherwise its deadline: « avant ce soir » when it falls
     * today — a due day has no hour (MAG-404) —, « limite ven. 13 » on another day. Nothing for a task done.
     */
    fun whenOf(task: Task, today: LocalDate): WhenChip? {
        if (task.isDone) return null
        if (task.scheduledDay == today) {
            task.scheduledAt?.let { return WhenChip(WhenKind.AT, "à $it") }
            momentLabel(task.scheduledMoment)?.let { return WhenChip(WhenKind.MOMENT, it) }
        }
        val due = task.dueDay ?: return null
        if (due == today) return WhenChip(WhenKind.DUE_TODAY, "avant ce soir")
        val format = if (due.month == today.month && due.year == today.year) dueDay else dueDayOtherMonth
        return WhenChip(WhenKind.DUE, "limite " + due.format(format))
    }

    fun momentLabel(moment: String?): String? = when (moment) {
        Task.MOMENT_MORNING -> "ce matin"
        Task.MOMENT_AFTERNOON -> "cet après-midi"
        Task.MOMENT_EVENING -> "ce soir"
        else -> null
    }

    /** The API's urgency (MAG-406) as a pill: critical and high only, and none once done. */
    fun urgencyOf(task: Task): UrgencyChip? = if (task.isDone) null else when (task.urgency) {
        "critical" -> UrgencyChip.CRITICAL
        "high" -> UrgencyChip.HIGH
        else -> null
    }

    /**
     * The rest of the meta: « faite à 9 h 12 » once done, else « reportée depuis lundi », else the estimate of a
     * task that is not timed — a timed one says its minutes on its timer.
     */
    fun metaOf(task: Task, today: LocalDate): String? {
        if (task.isDone) return doneAt(task.completedAt)?.let { "faite à $it" }
        TaskPostponed.labelOf(task.postponedSince, task.urgency, today)?.let { return it }
        return task.estimatedMinutes?.takeIf { !task.timed }?.let(::minutesLabel)
    }

    /** The timer's minutes (MAG-766): only for a task timed and not done; the Focus's 10 minutes without an estimate. */
    fun timerMinutesOf(task: Task): Int? =
        if (!task.timed || task.isDone) null else task.estimatedMinutes?.takeIf { it > 0 } ?: DEFAULT_TIMER_MINUTES

    /** « 9 h 12 », in Paris. */
    private fun doneAt(completedAt: String?): String? {
        val at = completedAt?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() } ?: return null
        val local = at.atZoneSameInstant(DateRanges.zone)
        return "${local.hour} h ${local.minute.toString().padStart(2, '0')}"
    }

    private val DEFAULT_TIMER_MINUTES = (FocusTimer.DEFAULT_MS / 60_000L).toInt()
}

package com.maggie.app.ui.screens.shared

import com.maggie.app.data.model.Task
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The « Programmée » of a task (MAG-718): the day the owner plans to do it, then a moment of that day —
 * morning, afternoon, evening — or a precise [time], the only one with [reminders]. Each change keeps the
 * API's rules: no day, nothing else; another moment than the time, no time and no reminder; the first
 * switch to a time, « À l'heure ».
 */
data class TaskSchedule(
    val day: LocalDate? = null,
    val moment: String? = null,
    val time: LocalTime? = null,
    val reminders: List<Int> = emptyList(),
) {
    val isAtTime: Boolean get() = moment == Task.MOMENT_TIME && time != null

    /** A day chosen keeps the moment; the day cleared clears it all. */
    fun onDay(day: LocalDate?): TaskSchedule = if (day == null) TaskSchedule() else copy(day = day)

    /** Morning, afternoon, evening or none: no time, no reminder. */
    fun atMoment(moment: String?): TaskSchedule = copy(moment = moment, time = null, reminders = emptyList())

    /** A precise time: the reminders already set stay, a first switch gets « À l'heure ». */
    fun atTime(time: LocalTime): TaskSchedule =
        copy(moment = Task.MOMENT_TIME, time = time, reminders = if (isAtTime) reminders else listOf(0))

    fun withReminders(reminders: List<Int>): TaskSchedule = if (isAtTime) copy(reminders = reminders) else this

    val scheduledDate: String? get() = day?.toString()
    val scheduledMoment: String? get() = moment.takeIf { day != null }
    val scheduledTime: String? get() = time?.takeIf { day != null && isAtTime }?.format(HH_MM)

    /** What a merge-patch sends: every key, null or empty to clear (MAG-718). */
    fun writeTo(body: JsonObjectBuilder) {
        body.put("scheduledDate", scheduledDate)
        body.put("scheduledMoment", scheduledMoment)
        body.put("scheduledTime", scheduledTime)
        body.put("reminders", JsonArray(remindersToApi().map(::JsonPrimitive)))
    }

    /** The reminders the API stores: only with a time. */
    fun remindersToApi(): List<Int> = if (day != null && isAtTime) reminders else emptyList()

    /** « vendredi · soir », « vendredi · 18:00 », « vendredi » for a bare day; a day past next week takes its date. */
    fun readLabel(today: LocalDate): String? {
        val day = day ?: return null
        val dayText = if (!day.isBefore(today) && day.isBefore(today.plusDays(7))) {
            day.format(WEEKDAY)
        } else {
            day.format(WEEKDAY_DATE)
        }
        val momentText = when {
            isAtTime -> time?.format(HH_MM)
            else -> moment?.let { READ_LABELS[it] }
        }
        return listOfNotNull(dayText, momentText).joinToString(" · ")
    }

    companion object {
        private val HH_MM = DateTimeFormatter.ofPattern("HH:mm")
        private val WEEKDAY = DateTimeFormatter.ofPattern("EEEE", Locale.FRENCH)
        private val WEEKDAY_DATE = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRENCH)

        /** The four moments of the « Moment » menu, in the day's order. */
        val MOMENT_LABELS: Map<String, String> = linkedMapOf(
            Task.MOMENT_MORNING to "Matin",
            Task.MOMENT_AFTERNOON to "Après-midi",
            Task.MOMENT_EVENING to "Soir",
            Task.MOMENT_TIME to "Heure précise",
        )

        private val READ_LABELS: Map<String, String> = mapOf(
            Task.MOMENT_MORNING to "matin",
            Task.MOMENT_AFTERNOON to "après-midi",
            Task.MOMENT_EVENING to "soir",
        )

        /** The time « Heure précise » opens on when none is set yet. */
        val DEFAULT_TIME: LocalTime = LocalTime.of(18, 0)

        /** The task's programmation as the form opens it; an unknown moment or a time without `time` is dropped. */
        fun of(task: Task?): TaskSchedule {
            val day = task?.scheduledDay ?: return TaskSchedule()
            val moment = task.scheduledMoment?.takeIf { it in MOMENT_LABELS }
            val time = task.scheduledAt
            return when {
                moment == Task.MOMENT_TIME && time == null -> TaskSchedule(day)
                moment == Task.MOMENT_TIME -> TaskSchedule(day, moment, time, task.reminders)
                else -> TaskSchedule(day, moment)
            }
        }
    }
}

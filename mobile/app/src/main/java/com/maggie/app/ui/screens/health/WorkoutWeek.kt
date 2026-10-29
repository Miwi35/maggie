package com.maggie.app.ui.screens.health

import com.maggie.app.data.model.Workout
import java.time.LocalDate
import java.time.ZoneId

/** What a day of « Cette semaine » shows: a session done (filled), planned (ringed), or nothing. */
enum class DayState { DONE, PLANNED, NONE }

/** One pill of the week: its letter, its day, its state, and whether it is [isToday]. */
data class WeekDay(val letter: String, val date: LocalDate, val state: DayState, val isToday: Boolean) {
    /** « L 20 : séance faite », « M 21 : séance prévue », « J 23 ». */
    val description: String
        get() = "$letter ${date.dayOfMonth}" + when (state) {
            DayState.DONE -> " : séance faite"
            DayState.PLANNED -> " : séance prévue"
            DayState.NONE -> ""
        }
}

/** The ISO week of [days] (Monday to Sunday) with its counts of sessions, not of days. */
data class WeekSummary(val days: List<WeekDay>, val done: Int, val planned: Int) {
    /** « 1 faite · 3 prévues », « 0 faite · 0 prévue ». */
    val counter: String
        get() = "$done ${if (done > 1) "faites" else "faite"} · $planned ${if (planned > 1) "prévues" else "prévue"}"
}

private val LETTERS = listOf("L", "M", "M", "J", "V", "S", "D")

/**
 * « Cette semaine » (MAG-705): the ISO week of [today] on the clock of [zone]. A day is done as soon as one session
 * that is not `planned` falls on it, planned if it only has planned ones. No clock in here: the caller says the day.
 */
fun weekOf(workouts: List<Workout>, today: LocalDate, zone: ZoneId): WeekSummary {
    val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
    val sunday = monday.plusDays(6)
    val inWeek = workouts.mapNotNull { workout -> workoutStart(workout, zone)?.toLocalDate()?.let { it to workout } }
        .filter { (day, _) -> day in monday..sunday }
    val days = (0..6).map { offset ->
        val date = monday.plusDays(offset.toLong())
        val ofDay = inWeek.filter { it.first == date }.map { it.second }
        val state = when {
            ofDay.any { !it.isPlanned } -> DayState.DONE
            ofDay.any { it.isPlanned } -> DayState.PLANNED
            else -> DayState.NONE
        }
        WeekDay(LETTERS[offset], date, state, isToday = date == today)
    }
    return WeekSummary(days, done = inWeek.count { !it.second.isPlanned }, planned = inWeek.count { it.second.isPlanned })
}

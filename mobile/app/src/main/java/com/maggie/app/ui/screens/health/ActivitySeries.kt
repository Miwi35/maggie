package com.maggie.app.ui.screens.health

import com.maggie.app.data.model.DailyEnergy
import com.maggie.app.data.model.HealthProfile
import com.maggie.app.data.model.Workout
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.roundToInt

/** How many days the « 7 jours » chart shows, today the last. */
const val ACTIVITY_WEEK_DAYS = 7

/** The projection waits this long after the window opens: before, the pace says nothing. */
const val PROJECTION_MIN_MINUTES = 60

/** An active session is a walk or a ride: the movement of the day, not a Sport session (MAG-898). */
fun isActiveSession(workout: Workout): Boolean = workout.type == WorkoutType.WALK.key || workout.type == WorkoutType.BIKE.key

private fun ofDay(workouts: List<Workout>, today: LocalDate, zone: ZoneId): List<Pair<java.time.ZonedDateTime, Workout>> =
    workouts.mapNotNull { workout -> workoutStart(workout, zone)?.let { it to workout } }
        .filter { (start, _) -> start.toLocalDate() == today }
        .sortedBy { it.first }

/** Today's walks and rides, done or planned, earliest first. */
fun activeSessions(workouts: List<Workout>, today: LocalDate, zone: ZoneId): List<Workout> =
    ofDay(workouts, today, zone).map { it.second }.filter(::isActiveSession)

/** Today's planned sessions that are not active ones: Sport's, only cited. */
fun plannedSportSessions(workouts: List<Workout>, today: LocalDate, zone: ZoneId): List<Workout> =
    ofDay(workouts, today, zone).map { it.second }.filter { it.isPlanned && !isActiveSession(it) }

/** « 6 249 » → 6 200: the nearest hundred. */
fun roundHundred(value: Int): Int = (value / 100.0).roundToInt() * 100

private fun minutesOf(time: String): Int? = runCatching { LocalTime.parse(time) }.getOrNull()?.let { it.hour * 60 + it.minute }

/**
 * The steps at the end of the window at today's pace: [steps] ÷ minutes since [windowStart] × the window's length, to
 * the hundred. `null` less than an hour after the window opens (and before it); after its end, the steps of the day.
 */
fun stepProjection(steps: Int, now: LocalTime, windowStart: String, windowEnd: String): Int? {
    val start = minutesOf(windowStart) ?: return null
    val end = minutesOf(windowEnd) ?: return null
    if (end <= start) return null
    val minute = now.hour * 60 + now.minute
    if (minute >= end) return steps
    val elapsed = minute - start
    if (elapsed < PROJECTION_MIN_MINUTES) return null
    return roundHundred((steps.toDouble() / elapsed * (end - start)).roundToInt())
}

/** What is missing to the goal at that pace, to the hundred; `null` once the projection reaches it (or without one). */
fun gapToGoal(projection: Int?, goal: Int): Int? =
    projection?.takeIf { it < goal }?.let { roundHundred(goal - it) }

/** One bar of « 7 jours »: the day, its steps (0 without data), and whether it reached the goal. */
data class WeekBar(val date: LocalDate, val steps: Int, val reached: Boolean, val isToday: Boolean) {
    /** « L », or « auj. » for today. */
    val label: String get() = if (isToday) "auj." else weekdayInitial(date.toString())
}

/** The seven days ending [today], J-6 first: a day the API did not send has no steps. */
fun weekBars(days: List<DailyEnergy>, goal: Int, today: LocalDate): List<WeekBar> {
    val byDate = days.associateBy { it.date }
    return (ACTIVITY_WEEK_DAYS - 1 downTo 0).map { back ->
        val date = today.minusDays(back.toLong())
        val steps = byDate[date.toString()]?.steps ?: 0
        WeekBar(date, steps, reached = steps >= goal, isToday = back == 0)
    }
}

/** No source: the profile names none and no step came in today. With steps today, the ring shows them. */
fun hasNoStepSource(profile: HealthProfile, todaySteps: Int?): Boolean =
    profile.stepSource == HealthProfile.STEP_SOURCE_NONE && (todaySteps ?: 0) == 0

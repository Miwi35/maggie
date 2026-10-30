package com.maggie.app.ui.screens.health

import com.maggie.app.data.model.Workout
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

private val DAYS = listOf("Lun.", "Mar.", "Mer.", "Jeu.", "Ven.", "Sam.", "Dim.")
private val MONTHS = listOf("janv.", "févr.", "mars", "avr.", "mai", "juin", "juil.", "août", "sept.", "oct.", "nov.", "déc.")

/** « 45 min », « 1 h 05 »: the duration of a session (MAG-705). */
fun formatWorkoutDuration(minutes: Int): String =
    if (minutes < 60) "$minutes min" else "${minutes / 60} h ${(minutes % 60).toString().padStart(2, '0')}"

/** « 5,4 km », « 22,0 km »: whole metres in kilometres, one decimal, a comma. */
fun formatWorkoutDistance(meters: Int): String = String.format(Locale.FRANCE, "%.1f km", meters / 1000.0)

/** « 386 kcal ». */
fun formatWorkoutKcal(kcal: Int): String = "$kcal kcal"

/** « 152 bpm ». */
fun formatWorkoutHeartRate(bpm: Int): String = "$bpm bpm"

/** « Lun. 20 oct. »: the weekday capitalised with its dot, whatever the phone's language. */
fun formatWorkoutDate(date: LocalDate): String =
    "${DAYS[date.dayOfWeek.value - 1]} ${date.dayOfMonth} ${MONTHS[date.monthValue - 1]}"

/** The start of [workout] on the clock of [zone]; null when the API's text is not a date-time. */
fun workoutStart(workout: Workout, zone: ZoneId): ZonedDateTime? =
    runCatching { OffsetDateTime.parse(workout.startedAt).atZoneSameInstant(zone) }.getOrNull()

/** « Lun. 20 oct. · 32 min · 5,4 km »: the day, the duration, and the distance when there is one. */
fun workoutMeta(workout: Workout, zone: ZoneId): String = listOfNotNull(
    workoutStart(workout, zone)?.let { formatWorkoutDate(it.toLocalDate()) },
    formatWorkoutDuration(workout.durationMinutes),
    workout.distanceMeters?.let(::formatWorkoutDistance),
).joinToString(" · ")

/**
 * What the Distance field holds, in whole metres: « 5,4 » is 5400. A comma or a dot, blank is "no distance" (null);
 * null too, as an error, is told apart by [isValidDistance].
 */
fun parseDistanceMeters(text: String): Int? {
    val value = runCatching { BigDecimal(text.trim().replace(',', '.').replace(" ", "")) }.getOrNull() ?: return null
    return runCatching { value.movePointRight(3).setScale(0, RoundingMode.HALF_UP).intValueExact() }.getOrNull()
}

/** « 5400 » metres back to the field: « 5,4 ». */
fun distanceInput(meters: Int): String =
    BigDecimal(meters).movePointLeft(3).stripTrailingZeros().toPlainString().replace('.', ',')

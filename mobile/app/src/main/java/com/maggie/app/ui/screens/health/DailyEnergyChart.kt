package com.maggie.app.ui.screens.health

import com.maggie.app.data.model.DailyEnergy
import java.time.LocalDate
import java.util.Locale

/** How many days the cards read once, the longest period: changing the period asks nothing of the API. */
const val ENERGY_HISTORY_DAYS = 365

/** The days a period shows: its length, 7, 30, 90 or 365, ending today. */
val SuiviPeriod.dayCount: Int get() = key.toInt()

/** The first day [period] shows, [today] being the last. */
fun energyStart(period: SuiviPeriod, today: LocalDate): LocalDate = today.minusDays(period.dayCount - 1L)

/** The rows of [period] ending [today], one per day: a day the API did not send is an empty one, oldest first. */
fun energyWindow(rows: List<DailyEnergy>, period: SuiviPeriod, today: LocalDate): List<DailyEnergy> {
    val byDate = rows.associateBy { it.date }
    val start = energyStart(period, today)
    return (0 until period.dayCount).map { offset ->
        val date = start.plusDays(offset.toLong()).toString()
        byDate[date] ?: DailyEnergy(date = date)
    }
}

/** Without a single day of steps there is no source to speak of: the Pas card stays empty, with no projection. */
fun hasSteps(window: List<DailyEnergy>): Boolean = window.any { it.steps != null }

/** The mean of the days that have steps, rounded; `null` when none does. */
fun averageSteps(window: List<DailyEnergy>): Int? =
    window.mapNotNull { it.steps }.takeIf { it.isNotEmpty() }?.average()?.let { Math.round(it).toInt() }

/** The day chosen, or the last one (today) when none is. */
fun selectedEnergy(window: List<DailyEnergy>, selected: LocalDate?): DailyEnergy? =
    window.firstOrNull { it.date == selected?.toString() } ?: window.lastOrNull()

/** The three parts of a day's stack; a part that does not exist stacks for nothing. */
data class EnergyParts(val base: Int, val steps: Int, val workouts: Int) {
    val height: Int get() = base + steps + workouts
}

fun energyParts(day: DailyEnergy) = EnergyParts(day.bmrKcal ?: 0, day.stepsKcal ?: 0, day.workoutKcal)

/** The tallest stack of the window, the scale of the bars. */
fun maxStack(window: List<DailyEnergy>): Int = window.maxOfOrNull { energyParts(it).height } ?: 0

/** `8 000`: thousands grouped by a plain space, which a screen reader and the journeys read as one. */
fun formatCount(value: Int): String = String.format(Locale.FRANCE, "%,d", value).replace(Regex("[\\s\\u00A0\\u202F]"), " ")

/** `2 052 kcal`, or the dash when the API has no total. */
fun formatKcal(value: Int?): String = value?.let { "${formatCount(it)} kcal" } ?: DASH

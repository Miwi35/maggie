package com.maggie.app.ui.screens.health

import com.maggie.app.data.model.BodyMeasurement
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/** The period selector of « Santé › Suivi » (MAG-706): 7 j · 30 j · 90 j · 1 an, 30 j by default. */
enum class SuiviPeriod(val key: String, val label: String) {
    WEEK("7", "7 j"),
    MONTH("30", "30 j"),
    QUARTER("90", "90 j"),
    YEAR("365", "1 an"),
    ;

    /** One column per day: the weighings are drawn as dots, a long press on a weighed day sets it aside. */
    val perDay: Boolean get() = this == WEEK || this == MONTH

    companion object {
        val DEFAULT = MONTH
    }
}

/**
 * A day of the history: [retained] is the first weighing of the local day that is not excluded and has a weight (the
 * API's `firstOfDay`); [weightKg] and [bodyFatPercent] are its own. [trendKg] is the mean of the day values of the day
 * and the six before it, `null` when none of them is weighed. [excluded] are the day's weighings set aside.
 */
data class WeightDay(
    val date: LocalDate,
    val retained: BodyMeasurement?,
    val trendKg: Double?,
    val excluded: List<BodyMeasurement>,
) {
    val weightKg: Double? get() = retained?.weightKg
    val bodyFatPercent: Double? get() = retained?.bodyFatPercent
}

/** A column of the card: one day (7 j, 30 j), a week (90 j) or a calendar month (1 an), with the means of its days. */
data class WeightColumn(
    val days: List<WeightDay>,
    /** The header's caption: `Aujourd’hui`, `mer. 21 oct.`, `Sem. du 14 oct.`, `Octobre 2026`. */
    val label: String,
    /** The axis's: `21 sept.`, `nov. 25`. */
    val short: String,
    val weightKg: Double?,
    val bodyFatPercent: Double?,
    val trendKg: Double?,
) {
    /** The weighing « Écarter » sets aside: that of a single-day column only. */
    val retained: BodyMeasurement? get() = days.singleOrNull()?.retained

    /** The weights of the weighings set aside, drawn dimmed. */
    val excludedWeights: List<Double> get() = days.flatMap { day -> day.excluded.mapNotNull { it.weightKg } }

    /** What a screen reader says of the column: `mer. 21 oct. : 72,4 kg, tendance 72,1 kg`. */
    val description: String
        get() = "$label : " + (weightKg?.let { "${formatKg(it)} kg" } ?: "pas de pesée") +
            ", tendance " + (trendKg?.let { "${formatKg(it)} kg" } ?: DASH)
}

/** The Poids card of a period: its columns oldest first, the axis's ends and the trend's change across the period. */
data class WeightChart(val period: SuiviPeriod, val columns: List<WeightColumn>) {
    val from: String get() = columns.firstOrNull()?.short.orEmpty()
    val to: String get() = columns.lastOrNull()?.short.orEmpty()

    /** `−1,2 kg`, `+0,3 kg`; `—` when the first or the last column has no trend. */
    val delta: String
        get() {
            val first = columns.firstOrNull()?.trendKg ?: return DASH
            val last = columns.lastOrNull()?.trendKg ?: return DASH
            val rounded = (last - first).roundTo1()
            return (if (rounded < 0) "−" else "+") + formatKg(abs(rounded)) + " kg"
        }

    /** The vertical range the values are drawn in: the weights and trends shown, 0,4 kg of margin each side. */
    val range: ClosedFloatingPointRange<Double>?
        get() {
            val values = columns.flatMap { listOfNotNull(it.weightKg, it.trendKg) }
            if (values.isEmpty()) return null
            return (values.min() - MARGIN_KG)..(values.max() + MARGIN_KG)
        }
}

/** What the card says where there is no value. */
const val DASH = "—"
private const val MARGIN_KG = 0.4
private const val TREND_DAYS = 7L
private const val QUARTER_WEEKS = 13
private const val YEAR_MONTHS = 12L

private val WEEKDAYS = listOf("lun.", "mar.", "mer.", "jeu.", "ven.", "sam.", "dim.")
private val MONTHS = listOf("janv.", "févr.", "mars", "avr.", "mai", "juin", "juil.", "août", "sept.", "oct.", "nov.", "déc.")
private val MONTH_NAMES = listOf(
    "Janvier", "Février", "Mars", "Avril", "Mai", "Juin", "Juillet", "Août", "Septembre", "Octobre", "Novembre", "Décembre",
)

/** `72,4`: one decimal, a comma. */
fun formatKg(value: Double): String = String.format(Locale.FRANCE, "%.1f", value.roundTo1())

private fun Double.roundTo1(): Double = (this * 10).roundToLong() / 10.0

/** `mer. 21 oct.` */
fun dayLabel(date: LocalDate): String = "${WEEKDAYS[date.dayOfWeek.value - 1]} ${date.dayOfMonth} ${MONTHS[date.monthValue - 1]}"

private fun shortDay(date: LocalDate): String = "${date.dayOfMonth} ${MONTHS[date.monthValue - 1]}"

private fun shortMonth(date: LocalDate): String = "${MONTHS[date.monthValue - 1]} ${date.year % 100}"

/** The first day the card can show: the first of the month eleven months back, less six days for its trend. */
fun historyStart(today: LocalDate): LocalDate = today.withDayOfMonth(1).minusMonths(YEAR_MONTHS - 1).minusDays(TREND_DAYS - 1)

private fun localDate(measurement: BodyMeasurement, zone: ZoneId): LocalDate? =
    runCatching { OffsetDateTime.parse(measurement.measuredAt).atZoneSameInstant(zone).toLocalDate() }.getOrNull()

private fun instantOf(measurement: BodyMeasurement) =
    runCatching { OffsetDateTime.parse(measurement.measuredAt).toInstant() }.getOrNull()

/** Every day from [from] to [to], each with its retained weighing and its trend, read on the clock of [zone]. */
fun weightDays(measurements: List<BodyMeasurement>, from: LocalDate, to: LocalDate, zone: ZoneId): List<WeightDay> {
    val byDay = measurements.mapNotNull { m -> localDate(m, zone)?.let { it to m } }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, ofDay) -> ofDay.sortedBy { instantOf(it) } }
    val retained = mutableMapOf<LocalDate, BodyMeasurement>()
    byDay.forEach { (day, ofDay) -> ofDay.firstOrNull { !it.excluded && it.weightKg != null }?.let { retained[day] = it } }

    val days = mutableListOf<WeightDay>()
    var date = from
    while (!date.isAfter(to)) {
        val window = (0 until TREND_DAYS).mapNotNull { retained[date.minusDays(it)]?.weightKg }
        days += WeightDay(
            date = date,
            retained = retained[date],
            trendKg = window.takeIf { it.isNotEmpty() }?.average(),
            excluded = byDay[date].orEmpty().filter { it.excluded },
        )
        date = date.plusDays(1)
    }
    return days
}

private fun meanOf(days: List<WeightDay>, value: (WeightDay) -> Double?): Double? =
    days.mapNotNull(value).takeIf { it.isNotEmpty() }?.average()

/**
 * The Poids card for [period] ending [today] (MAG-706, same rules as the admin's): 7 or 30 one-day columns, 13 weeks
 * over the last 91 days, or the twelve calendar months up to today's. A column of several days shows the mean of its
 * weighed days — weight, body fat and trend.
 */
fun weightChart(measurements: List<BodyMeasurement>, period: SuiviPeriod, today: LocalDate, zone: ZoneId): WeightChart {
    val groups: List<List<WeightDay>> = when (period) {
        SuiviPeriod.WEEK, SuiviPeriod.MONTH -> {
            val count = if (period == SuiviPeriod.WEEK) 7L else 30L
            weightDays(measurements, today.minusDays(count - 1), today, zone).map { listOf(it) }
        }
        SuiviPeriod.QUARTER -> weightDays(measurements, today.minusDays(QUARTER_WEEKS * 7L - 1), today, zone).chunked(7)
        SuiviPeriod.YEAR -> weightDays(measurements, today.withDayOfMonth(1).minusMonths(YEAR_MONTHS - 1), today, zone)
            .groupBy { it.date.withDayOfMonth(1) }.values.toList()
    }
    val columns = groups.map { days ->
        val first = days.first().date
        val label = when (period) {
            SuiviPeriod.WEEK, SuiviPeriod.MONTH -> if (first == today) "Aujourd’hui" else dayLabel(first)
            SuiviPeriod.QUARTER -> "Sem. du ${shortDay(first)}"
            SuiviPeriod.YEAR -> "${MONTH_NAMES[first.monthValue - 1]} ${first.year}"
        }
        WeightColumn(
            days = days,
            label = label,
            short = if (period == SuiviPeriod.YEAR) shortMonth(first) else shortDay(first),
            weightKg = meanOf(days) { it.weightKg },
            bodyFatPercent = meanOf(days) { it.bodyFatPercent },
            trendKg = meanOf(days) { it.trendKg },
        )
    }
    return WeightChart(period, columns)
}

/** The ISO date-time the list is asked from: the start of [historyStart] in [zone]. */
fun historyStartParameter(today: LocalDate, zone: ZoneId): String =
    ZonedDateTime.of(historyStart(today).atStartOfDay(), zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

package com.maggie.app.ui.screens.health

import com.maggie.app.data.model.DailyBalance
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/** Within ± this many kcal the day is in balance: the shaded band of the bar (MAG-748). */
const val BALANCE_ZONE_KCAL = 150

/** The narrowest half-range of the bar, so a day near zero does not fill it. */
const val BALANCE_BAR_MIN_HALF_RANGE = 600

/** How many days the « 7 jours » chart shows, today the last. */
const val BALANCE_WEEK_DAYS = 7

/** Where a day stands — a place on the bar, never a verdict: all three are drawn in soft colours. */
enum class BalanceZone { BALANCED, SURPLUS, DEFICIT }

/** |b| ≤ 150 is balanced; `null` without a balance (the profile is incomplete). */
fun balanceZone(balanceKcal: Int?): BalanceZone? = when {
    balanceKcal == null -> null
    abs(balanceKcal) <= BALANCE_ZONE_KCAL -> BalanceZone.BALANCED
    balanceKcal > 0 -> BalanceZone.SURPLUS
    else -> BalanceZone.DEFICIT
}

/** « Équilibre », « +320 kcal », « −480 kcal » (a true minus sign); `null` without a balance. */
fun balanceLabel(balanceKcal: Int?): String? = when (balanceZone(balanceKcal)) {
    null -> null
    BalanceZone.BALANCED -> "Équilibre"
    BalanceZone.SURPLUS -> "+${formatCount(balanceKcal!!)} kcal"
    BalanceZone.DEFICIT -> "−${formatCount(-balanceKcal!!)} kcal"
}

/** « Mangé 515 · Prévu 0 · Dépensé 1 652 kcal »; without an expenditure, « Mangé 515 · Prévu 0 kcal ». */
fun balanceFigures(balance: DailyBalance): String {
    val parts = mutableListOf("Mangé ${formatCount(balance.eatenKcal)}", "Prévu ${formatCount(balance.plannedKcal)}")
    balance.expendedKcal?.let { parts += "Dépensé ${formatCount(it)}" }
    return parts.joinToString(" · ") + " kcal"
}

/** The half-range of the bar for [balanceKcal]: wide enough for it, never under [BALANCE_BAR_MIN_HALF_RANGE]. */
fun balanceHalfRange(balanceKcal: Int?): Int = max(BALANCE_BAR_MIN_HALF_RANGE, abs(balanceKcal ?: 0))

/** Where [kcal] sits across a bar centred on zero, 0 at the left, 1 at the right. */
fun balanceFraction(kcal: Int, halfRange: Int): Float =
    (0.5f + kcal / (2f * halfRange.coerceAtLeast(1))).coerceIn(0f, 1f)

/** The seven days ending [today], oldest first: a day the API did not send has no balance. */
fun balanceWeek(rows: List<DailyBalance>, today: LocalDate): List<DailyBalance> {
    val byDate = rows.associateBy { it.date }
    return (BALANCE_WEEK_DAYS - 1 downTo 0).map { back ->
        val date = today.minusDays(back.toLong()).toString()
        byDate[date] ?: DailyBalance(date = date)
    }
}

/** The scale of the week's bars: the largest balance, never under the balanced band twice over. */
fun balanceWeekScale(week: List<DailyBalance>): Int =
    max(BALANCE_ZONE_KCAL * 2, week.maxOfOrNull { abs(it.balanceKcal ?: 0) } ?: 0)

/** `12,9 g`. */
fun formatGrams(value: Double): String = String.format(Locale.FRANCE, "%.1f g", value).replace(",0 g", " g")

/** « Déjeuner », « Dîner ». */
fun slotLabel(slot: String): String = when (slot) {
    "lunch" -> "Déjeuner"
    "dinner" -> "Dîner"
    else -> "Repas"
}

private val CLOCK_TIME = DateTimeFormatter.ofPattern("HH:mm")
private val ISO_WITH_OFFSET = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx")

/** `12:30` in [zone], or empty when the API sent no parsable time. */
fun intakeTime(eatenAt: String, zone: ZoneId): String =
    runCatching { OffsetDateTime.parse(eatenAt).atZoneSameInstant(zone).format(CLOCK_TIME) }.getOrDefault("")

/** `2026-11-07T12:30:00+01:00`, what `eatenAt` takes. */
fun isoWithOffset(dateTime: OffsetDateTime): String = dateTime.format(ISO_WITH_OFFSET)

/** The weekday's initial under a bar of the week: « L », « M »… */
fun weekdayInitial(date: String): String =
    runCatching { LocalDate.parse(date).dayOfWeek.value }.map { "LMMJVSD"[it - 1].toString() }.getOrDefault("")

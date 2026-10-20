package com.maggie.app.data.model

import kotlinx.serialization.Serializable
import java.time.LocalTime
import java.time.OffsetDateTime

/**
 * A routine (MAG-511), `GET /api/routines`: a moment of the day, a recurrence, a tick per day.
 *
 * - `anchor`: `wake`, `afterBreakfast`, `afterLunch`, `afterDinner` — the user's moments of the day
 *   (« Réglages › Tâches ») — or `time`, at [anchorTime] (`HH:MM`);
 * - `time`: the hour it resolves to, `HH:MM`, which sorts and groups it;
 * - `checkedToday` / `dueToday`: today in the user's zone, as the API reads it;
 * - `monthCount`: the ticks of the current month; `totalCount`: all of them;
 * - `recentDays`: the last seven days, the oldest first, today last.
 *
 * The flags are spelled as the API sends them (`active`, not `isActive`): see DtoContractTest.
 */
@Serializable
data class Routine(
    val id: String,
    val title: String,
    val anchor: String,
    val anchorTime: String? = null,
    val time: String,
    val recurrence: RoutineRecurrence,
    val active: Boolean = true,
    val remind: Boolean = false,
    val createdAt: String,
    val checkedToday: Boolean = false,
    val dueToday: Boolean = false,
    val monthCount: Int = 0,
    val totalCount: Int = 0,
    val recentDays: List<RoutineDay> = emptyList(),
) {
    /** The group of the screen it falls in: the part of the day for daily ones, else its rhythm. */
    val group: RoutineGroup get() = RoutineGroup.of(this)

    /** « au réveil », « le mardi, après le dîner », « le 15, à 22 h 30 ». */
    val whenLabel: String get() = RoutineText.whenOf(this)

    /** « ce mois : 13 fois », « depuis août : 9 fois ». */
    val totalLabel: String get() = RoutineText.totalOf(this)

    /** Ticked (or not) today: the counts and today's dot move with it, as the API would answer. */
    fun withCheckedToday(checked: Boolean): Routine {
        if (checked == checkedToday) return this
        val step = if (checked) 1 else -1
        return copy(
            checkedToday = checked,
            monthCount = (monthCount + step).coerceAtLeast(0),
            totalCount = (totalCount + step).coerceAtLeast(0),
            recentDays = recentDays.mapIndexed { i, day -> if (i == recentDays.lastIndex) day.copy(checked = checked) else day },
        )
    }

    companion object {
        const val ANCHOR_WAKE = "wake"
        const val ANCHOR_AFTER_BREAKFAST = "afterBreakfast"
        const val ANCHOR_AFTER_LUNCH = "afterLunch"
        const val ANCHOR_AFTER_DINNER = "afterDinner"
        const val ANCHOR_TIME = "time"

        val ANCHORS = listOf(ANCHOR_WAKE, ANCHOR_AFTER_BREAKFAST, ANCHOR_AFTER_LUNCH, ANCHOR_AFTER_DINNER, ANCHOR_TIME)
    }
}

/** `kind`: `daily`, `weekdays`, `weekly` (with [weekday], Monday = 1) or `monthly` (with [monthDay]). */
@Serializable
data class RoutineRecurrence(
    val kind: String,
    val weekday: Int? = null,
    val monthDay: Int? = null,
) {
    companion object {
        const val DAILY = "daily"
        const val WEEKDAYS = "weekdays"
        const val WEEKLY = "weekly"
        const val MONTHLY = "monthly"

        val KINDS = listOf(DAILY, WEEKDAYS, WEEKLY, MONTHLY)
    }
}

/** One of the last seven days: whether the routine was due that day, and ticked. */
@Serializable
data class RoutineDay(
    val date: String,
    val due: Boolean,
    val checked: Boolean,
)

/** The groups of the screen, in their order; an empty one is not drawn. */
enum class RoutineGroup(val key: String, val label: String) {
    MORNING("morning", "Matin"),
    AFTERNOON("afternoon", "Après-midi"),
    EVENING("evening", "Soir"),
    WEEKLY("weekly", "Chaque semaine"),
    MONTHLY("monthly", "Chaque mois"),
    ;

    companion object {
        private val NOON: LocalTime = LocalTime.of(12, 0)
        private val SIX_PM: LocalTime = LocalTime.of(18, 0)

        fun of(routine: Routine): RoutineGroup = when (routine.recurrence.kind) {
            RoutineRecurrence.WEEKLY -> WEEKLY
            RoutineRecurrence.MONTHLY -> MONTHLY
            else -> {
                val time = runCatching { LocalTime.parse(routine.time) }.getOrDefault(LocalTime.MIDNIGHT)
                when {
                    time < NOON -> MORNING
                    time < SIX_PM -> AFTERNOON
                    else -> EVENING
                }
            }
        }

        /** The routines in their groups, in the API's order within each, the empty groups left out. */
        fun grouped(routines: List<Routine>): List<Pair<RoutineGroup, List<Routine>>> {
            val byGroup = routines.groupBy { it.group }
            return entries.mapNotNull { group -> byGroup[group]?.takeIf { it.isNotEmpty() }?.let { group to it } }
        }
    }
}

/** How a routine reads on its card and in its sheet. */
object RoutineText {

    private val WEEKDAYS = listOf("lundi", "mardi", "mercredi", "jeudi", "vendredi", "samedi", "dimanche")

    private val MONTHS = listOf(
        "janvier", "février", "mars", "avril", "mai", "juin",
        "juillet", "août", "septembre", "octobre", "novembre", "décembre",
    )

    /** The moment as the card says it. */
    fun anchorOf(anchor: String, time: String?): String = when (anchor) {
        Routine.ANCHOR_WAKE -> "au réveil"
        Routine.ANCHOR_AFTER_BREAKFAST -> "après le petit-déjeuner"
        Routine.ANCHOR_AFTER_LUNCH -> "après le déjeuner"
        Routine.ANCHOR_AFTER_DINNER -> "après le dîner"
        else -> time?.let { "à ${hourOf(it)}" } ?: "à une heure"
    }

    /** The moment as a chip of the sheet says it. */
    fun chipOf(anchor: String): String = when (anchor) {
        Routine.ANCHOR_WAKE -> "Au réveil"
        Routine.ANCHOR_AFTER_BREAKFAST -> "Après le petit-déj"
        Routine.ANCHOR_AFTER_LUNCH -> "Après le déjeuner"
        Routine.ANCHOR_AFTER_DINNER -> "Après le dîner"
        else -> "À une heure"
    }

    /** « 22 h 30 », « 7 h », « 7 h 05 ». */
    fun hourOf(time: String): String {
        val parsed = runCatching { LocalTime.parse(time) }.getOrNull() ?: return time
        return if (parsed.minute == 0) "${parsed.hour} h" else "${parsed.hour} h ${"%02d".format(parsed.minute)}"
    }

    /** « lundi » for 1 … « dimanche » for 7. */
    fun weekdayOf(weekday: Int): String = WEEKDAYS[(weekday - 1).coerceIn(0, 6)]

    /** « 1er », « 15 ». */
    fun monthDayOf(day: Int): String = if (day == 1) "1er" else day.toString()

    fun whenOf(routine: Routine): String {
        val moment = anchorOf(routine.anchor, routine.anchorTime ?: routine.time)
        val recurrence = routine.recurrence
        return when (recurrence.kind) {
            RoutineRecurrence.WEEKLY -> recurrence.weekday?.let { "le ${weekdayOf(it)}, $moment" } ?: moment
            RoutineRecurrence.MONTHLY -> recurrence.monthDay?.let { "le ${monthDayOf(it)}, $moment" } ?: moment
            RoutineRecurrence.WEEKDAYS -> "en semaine, $moment"
            else -> moment
        }
    }

    /** « 1 fois », « 13 fois ». */
    fun timesOf(count: Int): String = "$count fois"

    fun totalOf(routine: Routine): String = when (routine.recurrence.kind) {
        RoutineRecurrence.WEEKLY, RoutineRecurrence.MONTHLY -> "depuis ${monthOf(routine.createdAt)} : ${timesOf(routine.totalCount)}"
        else -> "ce mois : ${timesOf(routine.monthCount)}"
    }

    /** The month of an ATOM date, « août ». */
    private fun monthOf(createdAt: String): String {
        val month = runCatching { OffsetDateTime.parse(createdAt).monthValue }.getOrNull()
            ?: createdAt.takeIf { it.length >= 7 }?.substring(5, 7)?.toIntOrNull()?.takeIf { it in 1..12 }
            ?: return "le début"
        return MONTHS[month - 1]
    }

    /** The sheet's repeat chips: « Chaque mardi » names the day chosen. */
    fun repeatOf(kind: String, weekday: Int): String = when (kind) {
        RoutineRecurrence.WEEKDAYS -> "Jours de semaine"
        RoutineRecurrence.WEEKLY -> "Chaque ${weekdayOf(weekday)}"
        RoutineRecurrence.MONTHLY -> "Chaque mois"
        else -> "Chaque jour"
    }
}

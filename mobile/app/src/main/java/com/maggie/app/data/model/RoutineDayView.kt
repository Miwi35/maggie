package com.maggie.app.data.model

import kotlinx.serialization.Serializable
import java.time.LocalTime

/**
 * The routines due on one day, `GET /api/routines/day/{date}` (MAG-512), grouped by moment in the
 * order of the user's hours: what « Aujourd'hui » and the home widget show.
 */
@Serializable
data class RoutineDayView(
    val date: String,
    val moments: List<RoutineMoment> = emptyList(),
) {
    val routines: List<DayRoutine> get() = moments.flatMap { it.routines }

    val dueCount: Int get() = routines.size

    val doneCount: Int get() = routines.count { it.checked }

    /** « 1 sur 3 aujourd'hui ». */
    val summary: String get() = "$doneCount sur $dueCount aujourd'hui"

    /** The tick shown at once, as the API will answer it. */
    fun withChecked(routineId: String, checked: Boolean): RoutineDayView = copy(
        moments = moments.map { moment ->
            moment.copy(routines = moment.routines.map { if (it.id == routineId) it.copy(checked = checked) else it })
        },
    )
}

/** A moment of the day and the routines due at it; [time] is `HH:MM`, [anchor] as in [Routine]. */
@Serializable
data class RoutineMoment(
    val anchor: String,
    val time: String,
    val routines: List<DayRoutine> = emptyList(),
) {
    val timeOfDay: LocalTime? get() = runCatching { LocalTime.parse(time) }.getOrNull()

    /** « 7 h 30 ». */
    val hourLabel: String get() = RoutineText.hourOf(time)

    /** « Au réveil · 7 h 30 », « 22 h 30 » for a routine at an hour of its own. */
    val label: String
        get() = if (anchor == Routine.ANCHOR_TIME) hourLabel else "${RoutineText.chipOf(anchor)} · $hourLabel"
}

/** A routine of a day: [checked] says whether that day's tick is given. */
@Serializable
data class DayRoutine(
    val id: String,
    val title: String,
    val anchor: String,
    val anchorTime: String? = null,
    val time: String,
    val remind: Boolean = false,
    val checked: Boolean = false,
)

/**
 * The band of « Aujourd'hui » (MAG-512): [routines] to tick now, under « Routines · [moment] ».
 * Never counted in the day's ring, never in « Maintenant ».
 */
data class RoutinesBand(val moment: String, val routines: List<DayRoutine>) {
    val title: String get() = "Routines · $moment"

    companion object {
        private val NOON: LocalTime = LocalTime.of(12, 0)
        private val SIX_PM: LocalTime = LocalTime.of(18, 0)

        /** « matin », « après-midi » or « soir »: the part of the day of an hour. */
        fun momentOf(time: LocalTime): String = when {
            time < NOON -> "matin"
            time < SIX_PM -> "après-midi"
            else -> "soir"
        }

        /**
         * The current moment is the last whose hour has passed ([now]), the first one before it all;
         * the band holds its routines, ticked or not, and the routines of earlier moments still to tick.
         * Null when there is nothing to show.
         */
        fun of(day: RoutineDayView?, now: LocalTime): RoutinesBand? {
            val timed = day?.moments.orEmpty().mapNotNull { moment -> moment.timeOfDay?.let { it to moment } }
            if (timed.isEmpty()) return null
            val currentTime = timed.map { it.first }.filter { it <= now }.maxOrNull() ?: timed.minOf { it.first }
            val routines = timed.sortedBy { it.first }.flatMap { (time, moment) ->
                when {
                    time == currentTime -> moment.routines
                    time < currentTime -> moment.routines.filterNot { it.checked }
                    else -> emptyList()
                }
            }
            return routines.takeIf { it.isNotEmpty() }?.let { RoutinesBand(momentOf(currentTime), it) }
        }
    }
}

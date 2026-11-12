package com.maggie.app.ui.screens.health.sport

import com.maggie.app.data.model.WorkoutExercise
import com.maggie.app.data.model.WorkoutSetDone
import com.maggie.app.data.model.WorkoutUnit
import com.maggie.app.ui.screens.health.formatLoadKg
import kotlin.math.abs

/** What changed since the last session done: « +1 rép. », « +5 kg », or « = » ([same]). */
data class SportDelta(val text: String, val same: Boolean)

private fun WorkoutSetDone.count(unit: String): Int = when (unit) {
    WorkoutUnit.SECONDS -> seconds
    WorkoutUnit.METERS -> meters
    else -> reps
} ?: 0

private fun WorkoutExercise.total(): Int = done.sumOf { it.count(unit) }

private fun WorkoutExercise.load(): Double = done.firstNotNullOfOrNull { it.loadKg } ?: loadKg ?: 0.0

private fun sign(value: Double) = if (value > 0) "+" else "−"

/**
 * The gap to the last session done that held the exercise (MAG-781): the load when it changed, else the sum of the
 * repetitions (seconds, metres) done. Null without a last time.
 */
fun sportDelta(now: WorkoutExercise, last: WorkoutExercise?): SportDelta? {
    if (last == null) return null
    val loadGap = now.load() - last.load()
    if (loadGap != 0.0) return SportDelta("${sign(loadGap)}${formatLoadKg(abs(loadGap))} kg", same = false)
    val gap = now.total() - last.total()
    if (gap == 0) return SportDelta("=", same = true)
    val unit = when (now.unit) {
        WorkoutUnit.SECONDS -> "s"
        WorkoutUnit.METERS -> "m"
        else -> "rép."
    }
    return SportDelta("${sign(gap.toDouble())}${abs(gap)} $unit", same = false)
}

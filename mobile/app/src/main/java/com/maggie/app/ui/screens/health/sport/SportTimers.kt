package com.maggie.app.ui.screens.health.sport

import com.maggie.app.data.model.WorkoutExercise
import com.maggie.app.data.model.WorkoutUnit

/** « 1:22 »: the ring's time, minutes then two-digit seconds. */
fun ringClock(seconds: Int): String {
    val left = seconds.coerceAtLeast(0)
    return "%d:%02d".format(left / 60, left % 60)
}

/** A count in the exercise's unit: « 5 », « 30 s », « 20 m ». */
fun countLabel(exercise: WorkoutExercise, value: Int): String = when (exercise.unit) {
    WorkoutUnit.SECONDS -> "$value s"
    WorkoutUnit.METERS -> "$value m"
    else -> "$value"
}

/** « 3 × 5 », « · 10 kg » when loaded. */
fun planLabel(exercise: WorkoutExercise, loadKg: Double?): String {
    val plan = "${exercise.sets} × ${countLabel(exercise, exercise.target)}"
    return if (loadKg != null && loadKg > 0) "$plan · ${loadLabel(loadKg)}" else plan
}

/** The sentence under the ring. */
fun ringCaption(phase: String, running: Boolean): String = when {
    phase == SportPhase.REST -> "Repos"
    phase == SportPhase.HOLD && running -> "Tenez"
    phase == SportPhase.HOLD -> "Pause"
    else -> "Prêt"
}

/** The lest card's value: « 10 kg », « 7,5 kg ». */
fun loadLabel(kg: Double): String = "${java.math.BigDecimal.valueOf(kg).stripTrailingZeros().toPlainString().replace('.', ',')} kg"

/** « Ensuite · … » while a set is on: its rest and the next set, else the next exercise, else the end. */
fun nextInSet(exercises: List<WorkoutExercise>, state: SportState): String {
    val exercise = exercises[state.exerciseIndex]
    return when {
        state.setIndex + 1 < exercise.sets ->
            "Repos ${ringClock(exercise.restSeconds ?: 0)} · série ${state.setIndex + 2}"
        state.exerciseIndex + 1 < exercises.size -> exercises[state.exerciseIndex + 1].name
        else -> "Fin de la séance"
    }
}

/** « Ensuite · … » while resting: the next set, else the next exercise's plan, else the end. */
fun nextInRest(exercises: List<WorkoutExercise>, state: SportState): String {
    val exercise = exercises[state.exerciseIndex]
    return when {
        state.setIndex + 1 < exercise.sets -> "Série ${state.setIndex + 2} · ${countLabel(exercise, exercise.target)}"
        state.exerciseIndex + 1 < exercises.size -> exercises[state.exerciseIndex + 1].let { "${it.name} · ${it.sets} × ${countLabel(it, it.target)}" }
        else -> "Fin de la séance"
    }
}

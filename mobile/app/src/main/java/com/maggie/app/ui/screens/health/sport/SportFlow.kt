package com.maggie.app.ui.screens.health.sport

import com.maggie.app.data.model.WorkoutExercise
import com.maggie.app.data.model.WorkoutUnit
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

/** What a clock tick or a resume found: the state, whether a timer ran out, and whether it was the session's last set. */
data class Settled(val state: SportState, val expired: Boolean, val finished: Boolean)

/**
 * The moves of a Sport session (MAG-780), pure: a state and a clock in, the next state out. The timers hang on
 * `timerEndsAt`, so they go on with the app closed and fall right on a resume.
 */
class SportFlow(private val exercises: List<WorkoutExercise>) {

    fun exercise(state: SportState): WorkoutExercise = exercises[state.exerciseIndex]

    fun isHeld(exercise: WorkoutExercise) = exercise.unit == WorkoutUnit.SECONDS

    fun isLastSet(state: SportState): Boolean =
        state.exerciseIndex == exercises.lastIndex && state.setIndex == exercise(state).sets - 1

    /** Every set of the session is done: the last set of the last exercise holds its entry. */
    fun isComplete(state: SportState): Boolean = isLastSet(state) && state.done.getOrNull(state.exerciseIndex)?.size == exercise(state).sets

    /** Seconds left on the running or paused timer; the target of a held exercise not started; null when there is no timer. */
    fun remaining(state: SportState, now: Instant): Int? {
        endsAt(state)?.let { return ceilSeconds(Duration.between(now, it).toMillis()) }
        state.timerRemaining?.let { return it }
        return exercise(state).takeIf { isHeld(it) && state.phase == SportPhase.WORK }?.target
    }

    /** The seconds held when « Série faite » is touched on a held exercise: what has gone from its target. */
    fun heldSeconds(state: SportState, now: Instant): Int {
        val target = exercise(state).target
        return if (state.phase == SportPhase.WORK) target else (target - (remaining(state, now) ?: target)).coerceIn(0, target)
    }

    /** Records the set at the pointer, then rests (or moves on without rest). The last set of the session stays where it is. */
    fun complete(state: SportState, value: Int, at: Instant): SportState {
        val exercise = exercise(state)
        val index = state.exerciseIndex
        val entry = when (exercise.unit) {
            WorkoutUnit.SECONDS -> SportSetDone(seconds = value, loadKg = state.loadKg.getOrNull(index))
            WorkoutUnit.METERS -> SportSetDone(meters = value, loadKg = state.loadKg.getOrNull(index))
            else -> SportSetDone(reps = value, loadKg = state.loadKg.getOrNull(index))
        }
        val done = state.done.mapIndexed { i, sets -> if (i == index) sets.take(state.setIndex) + entry else sets }
        val recorded = state.copy(done = done, phase = SportPhase.WORK, timerEndsAt = null, timerRemaining = null)
        if (isLastSet(state)) return recorded
        val rest = exercise.restSeconds ?: 0
        return if (rest > 0) recorded.copy(phase = SportPhase.REST, timerEndsAt = at.plusSeconds(rest.toLong()).toString()) else advance(recorded)
    }

    /** The next set, or the first of the next exercise. */
    fun advance(state: SportState): SportState {
        val next = if (state.setIndex + 1 < exercise(state).sets) {
            state.copy(setIndex = state.setIndex + 1)
        } else {
            state.copy(exerciseIndex = (state.exerciseIndex + 1).coerceAtMost(exercises.lastIndex), setIndex = 0)
        }
        return next.copy(phase = SportPhase.WORK, timerEndsAt = null, timerRemaining = null)
    }

    /** « ‹ »: rest → the set just done; set → the previous one; first set → the previous exercise's last. Null on the very first set. */
    fun back(state: SportState): SportState? {
        val idle = state.copy(phase = SportPhase.WORK, timerEndsAt = null, timerRemaining = null)
        return when {
            state.phase == SportPhase.REST -> idle
            state.setIndex > 0 -> idle.copy(setIndex = state.setIndex - 1)
            state.exerciseIndex > 0 -> idle.copy(exerciseIndex = state.exerciseIndex - 1, setIndex = exercises[state.exerciseIndex - 1].sets - 1)
            else -> null
        }
    }

    /** « Démarrer »: a held exercise counts down from its target, or from where it was paused. */
    fun startHold(state: SportState, now: Instant): SportState {
        val seconds = state.timerRemaining ?: exercise(state).target
        return state.copy(phase = SportPhase.HOLD, timerEndsAt = now.plusSeconds(seconds.toLong()).toString(), timerRemaining = null)
    }

    /** « Pause »: the seconds left are kept, the end is dropped. */
    fun pauseHold(state: SportState, now: Instant): SportState =
        state.copy(phase = SportPhase.HOLD, timerEndsAt = null, timerRemaining = remaining(state, now))

    /** « −15 » / « +15 » on a rest. */
    fun adjustRest(state: SportState, seconds: Int): SportState {
        val ends = endsAt(state) ?: return state
        return state.copy(timerEndsAt = ends.plusSeconds(seconds.toLong()).toString())
    }

    /** Applies what the clock has already ended: a rest gives the next set, a held exercise its set with the target duration. */
    fun settle(state: SportState, now: Instant): Settled {
        var current = state
        var expired = false
        repeat(MAX_SETTLE) {
            val ends = endsAt(current) ?: return Settled(current, expired, false)
            if (ends.isAfter(now)) return Settled(current, expired, false)
            expired = true
            when (current.phase) {
                SportPhase.REST -> current = advance(current)
                SportPhase.HOLD -> {
                    if (isLastSet(current)) return Settled(complete(current, exercise(current).target, ends), true, true)
                    current = complete(current, exercise(current).target, ends)
                }
                else -> current = current.copy(timerEndsAt = null)
            }
        }
        return Settled(current, expired, false)
    }

    fun endsAt(state: SportState): Instant? = state.timerEndsAt?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() }

    private fun ceilSeconds(millis: Long): Int = if (millis <= 0) 0 else ((millis + 999) / 1000).toInt()

    private companion object {
        const val MAX_SETTLE = 8
    }
}

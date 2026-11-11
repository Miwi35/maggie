package com.maggie.app.ui.screens.health.sport

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.model.Workout
import com.maggie.app.data.repository.ActivityStatusRepository
import com.maggie.app.data.repository.WorkoutRepository
import com.maggie.app.ui.navigation.DeepLinks
import com.maggie.app.ui.screens.health.formatDose
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant

enum class SportLoad {
    LOADING,

    /** The API is unreachable: « Réessayer », and the activity never closes by itself. */
    ERROR,

    /** The session does not exist any more, or has no exercise to do. */
    NOT_FOUND,
    READY,
}

data class SportActivityUiState(
    val load: SportLoad = SportLoad.LOADING,
    val workout: Workout? = null,
    /** Where the session stands: the exercise and set on screen, the phase and its timer, the sets done. */
    val session: SportState = SportState(),
    /** The repetitions (or metres) on the stepper, for the set on screen: starts at the target. */
    val reps: Int = 0,
    /** Seconds left on the ring (rest, held exercise), null when there is none. */
    val remaining: Int? = null,
    /** The last set was done: the activity moves on to its end. */
    val finished: Boolean = false,
    /** Exercise slug → « 3 × 4 »: what the last session done that held it says. */
    val lastTimes: Map<String, String> = emptyMap(),
    /** « Quitter la séance ? » is asked. */
    val quitAsked: Boolean = false,
) {
    /** 0-based: the exercise on screen. */
    val exerciseIndex: Int get() = session.exerciseIndex
    val setIndex: Int get() = session.setIndex
    val phase: String get() = session.phase
    val exercises get() = workout?.exercises.orEmpty()
    val current get() = exercises.getOrNull(exerciseIndex)
    val loadKg: Double? get() = session.loadKg.getOrNull(exerciseIndex)
    val timerRunning: Boolean get() = session.timerEndsAt != null
    val count: Int get() = exercises.size
}

/**
 * The Sport activity (MAG-779): opens the session it is given — by its id, or by the agenda event it is filed under —,
 * writes the starting `activity-status` unless one already holds this session, and resumes where it stopped.
 * MAG-780 makes it move: each step writes the status, and the timers hang on its `timerEndsAt`.
 */
class SportActivityViewModel(
    savedState: SavedStateHandle,
    private val workoutRepository: WorkoutRepository,
    private val statusRepository: ActivityStatusRepository,
    private val clock: () -> Instant = Instant::now,
    private val sound: SportSound = ToneSportSound(),
) : ViewModel() {

    private val workoutId: String? = savedState.get<String>(DeepLinks.ARG_WORKOUT)?.takeIf { it != NO_WORKOUT && DeepLinks.isValidId(it) }
    private val eventId: String? = savedState.get<String>(DeepLinks.ARG_EVENT)?.takeIf(DeepLinks::isValidId)

    private val _uiState = MutableStateFlow(SportActivityUiState())
    val uiState: StateFlow<SportActivityUiState> = _uiState

    init {
        open()
    }

    fun retry() {
        _uiState.update { SportActivityUiState() }
        open()
    }

    private fun open() {
        viewModelScope.launch {
            val found = when {
                workoutId != null -> workoutRepository.getWorkout(workoutId)
                eventId != null -> workoutRepository.getWorkoutOfEvent(eventId)
                else -> Result.success(null)
            }
            val workout = found.getOrElse { return@launch fail(SportLoad.ERROR) }
            if (workout == null || workout.exercises.isEmpty()) return@launch fail(SportLoad.NOT_FOUND)

            // A read that fails never overwrites: the status may hold progress made on another device.
            val status = statusRepository.get(SPORT_ACTIVITY).getOrElse { return@launch fail(SportLoad.ERROR) }
            val flow = SportFlow(workout.exercises)
            val saved = status?.takeIf { it.toSportResume()?.let { r -> r.workoutId == workout.id && r.exerciseCount == workout.exercises.size } == true }
                ?.toSportState()?.takeIf { it.isConsistentWith(workout) }
                ?.let { if (it.loadKg.size == workout.exercises.size) it else it.copy(loadKg = workout.exercises.map { e -> e.loadKg }) }
            val settled = flow.settle(saved ?: sportStart(workout), clock())
            if (saved == null || settled.state != saved) write(workout, settled.state)

            this@SportActivityViewModel.flow = flow
            _uiState.update {
                it.copy(load = SportLoad.READY, workout = workout, finished = settled.finished).withSession(settled.state, flow, clock(), resetReps = true)
            }
            syncTicker()
            loadLastTimes(workout)
        }
    }

    private var flow: SportFlow? = null
    private var ticker: Job? = null

    // --- the steps ---------------------------------------------------------------------------------------------

    /** « Série faite »: the reps (or the time held) are written to `done`, then the rest starts. */
    fun setDone() = step { flow, state, now ->
        val value = if (flow.isHeld(flow.exercise(state))) flow.heldSeconds(state, now) else _uiState.value.reps
        val finished = flow.isLastSet(state)
        flow.complete(state, value, now) to finished
    }

    /** « Passer le repos ». */
    fun skipRest() = step { flow, state, _ -> flow.advance(state) to false }

    /** « ‹ »: one step back. */
    fun back() = step { flow, state, _ -> (flow.back(state) ?: state) to false }

    /** « Démarrer » on a held exercise. */
    fun startHold() = step { flow, state, now -> flow.startHold(state, now) to false }

    /** « Pause » on a held exercise. */
    fun pauseHold() = step { flow, state, now -> flow.pauseHold(state, now) to false }

    /** « −15 » / « +15 » on the rest. */
    fun adjustRest(seconds: Int) = step { flow, state, now ->
        val moved = flow.adjustRest(state, seconds)
        // A rest cut to nothing is over.
        (if (flow.endsAt(moved)?.isAfter(now) == false) flow.advance(moved) else moved) to false
    }

    /** The stepper's « − » / « + », for the set on screen: nothing is written until « Série faite ». */
    fun changeReps(delta: Int) = _uiState.update { it.copy(reps = (it.reps + delta).coerceAtLeast(0)) }

    /** The lest's « − » / « + » (5 kg, never below 0): the load goes to the status and onto the next sets. */
    fun changeLoad(deltaKg: Double) = step { _, state, _ ->
        val loads = state.loadKg.toMutableList()
        val index = state.exerciseIndex
        loads[index] = ((loads[index] ?: 0.0) + deltaKg).coerceAtLeast(0.0)
        state.copy(loadKg = loads) to false
    }

    private fun step(move: (SportFlow, SportState, Instant) -> Pair<SportState, Boolean>) {
        val flow = flow ?: return
        val ui = _uiState.value
        if (ui.load != SportLoad.READY || ui.finished) return
        val now = clock()
        val (next, finished) = move(flow, ui.session, now)
        commit(next, finished, resetReps = next.exerciseIndex != ui.session.exerciseIndex || next.setIndex != ui.session.setIndex || next.phase != ui.session.phase)
    }

    private fun commit(next: SportState, finished: Boolean, resetReps: Boolean) {
        val workout = _uiState.value.workout ?: return
        val flow = flow ?: return
        write(workout, next)
        _uiState.update { it.copy(finished = finished).withSession(next, flow, clock(), resetReps) }
        syncTicker()
    }

    private fun write(workout: Workout, state: SportState) = statusRepository.put(SPORT_ACTIVITY, sportOrigin(workout.id), state.toJson())

    private fun SportActivityUiState.withSession(next: SportState, flow: SportFlow, now: Instant, resetReps: Boolean): SportActivityUiState {
        val exercise = flow.exercise(next)
        val recorded = next.done.getOrNull(next.exerciseIndex)?.getOrNull(next.setIndex)
        val start = recorded?.let { it.reps ?: it.meters } ?: exercise.target
        return copy(session = next, remaining = flow.remaining(next, now), reps = if (resetReps) start else reps)
    }

    // --- the clock ---------------------------------------------------------------------------------------------

    private fun syncTicker() {
        if (_uiState.value.session.timerEndsAt == null) {
            ticker?.cancel()
            ticker = null
        } else if (ticker?.isActive != true) {
            ticker = viewModelScope.launch {
                while (isActive && _uiState.value.session.timerEndsAt != null) {
                    delay(TICK_MS)
                    tick()
                }
            }
        }
    }

    private fun tick() {
        val flow = flow ?: return
        val ui = _uiState.value
        val now = clock()
        val settled = flow.settle(ui.session, now)
        if (settled.state != ui.session || settled.finished) {
            if (settled.expired) sound.beep()
            commit(settled.state, settled.finished, resetReps = true)
        } else {
            _uiState.update { it.copy(remaining = flow.remaining(it.session, now)) }
        }
    }

    private fun SportState.isConsistentWith(workout: Workout): Boolean {
        val exercises = workout.exercises
        return exerciseIndex in exercises.indices && setIndex in 0 until exercises[exerciseIndex].sets &&
            done.size == exercises.size
    }

    private fun fail(load: SportLoad) = _uiState.update { it.copy(load = load) }

    // « La dernière fois »: per exercise, from the latest session done that held it; a failed read just leaves the line out.
    private suspend fun loadLastTimes(workout: Workout) {
        workout.exercises.forEach { exercise ->
            val last = workoutRepository.getWorkoutsWithExercise(exercise.exercise).getOrNull()
                ?.firstOrNull { !it.isPlanned && it.id != workout.id && it.exercises.any { e -> e.exercise == exercise.exercise && e.done.isNotEmpty() } }
                ?.exercises?.firstOrNull { it.exercise == exercise.exercise && it.done.isNotEmpty() }
                ?: return@forEach
            _uiState.update { it.copy(lastTimes = it.lastTimes + (exercise.exercise to formatDose(last))) }
        }
    }

    fun askQuit() = _uiState.update { it.copy(quitAsked = true) }

    fun dismissQuit() = _uiState.update { it.copy(quitAsked = false) }

    companion object {
        private const val TICK_MS = 200L

        /** The path segment of a launch by event, where the session's id is not known yet. */
        const val NO_WORKOUT = "-"
    }
}

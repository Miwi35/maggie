package com.maggie.app.ui.screens.health.sport

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.api.SportWorkoutCreate
import com.maggie.app.data.api.SportWorkoutFinish
import com.maggie.app.data.api.WorkoutExerciseWrite
import com.maggie.app.data.api.WorkoutSetWrite
import com.maggie.app.data.health.HealthConnectSync
import com.maggie.app.data.model.Workout
import com.maggie.app.data.model.WorkoutExercise
import com.maggie.app.data.model.WorkoutSetDone
import com.maggie.app.data.model.WorkoutSource
import com.maggie.app.data.repository.ActivityStatusRepository
import com.maggie.app.data.repository.WorkoutRepository
import com.maggie.app.ui.screens.health.WorkoutType
import com.maggie.app.util.rethrowCancellation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

enum class SportEndLoad {
    LOADING,

    /** The session done could not be written: « Réessayer », the sets stay in the status. */
    ERROR,

    /** Nothing to end: no status, or one this version cannot read. */
    GONE,
    READY,
}

/** What the screen draws for one exercise: the session's done sets, the gap to the last time, how it felt. */
data class SportEndRow(val exercise: WorkoutExercise, val delta: SportDelta?, val feeling: String?)

data class SportEndUiState(
    val load: SportEndLoad = SportEndLoad.LOADING,
    /** The planned session the activity was launched from. */
    val planned: Workout? = null,
    /** The session done, with every set. */
    val done: Workout? = null,
    /** Exercise slug → the gap to the last session done that held it. */
    val deltas: Map<String, SportDelta> = emptyMap(),
    /** One entry per exercise, null while the owner has not said. */
    val feelings: List<String?> = emptyList(),
    val difficulty: Int? = null,
    val saving: Boolean = false,
    /** « Enregistrer » failed: the choices stay, the button reads « Réessayer ». */
    val saveFailed: Boolean = false,
    /** Saved: the screen goes back to Santé › Sport. */
    val saved: Boolean = false,
    val quitAsked: Boolean = false,
) {
    val exercises: List<WorkoutExercise> get() = done?.exercises.orEmpty()
    val rows: List<SportEndRow> get() = exercises.mapIndexed { i, e -> SportEndRow(e, deltas[e.exercise], feelings.getOrNull(i)) }
    val streak: Int? get() = done?.streak?.takeIf { it > 0 }
    val minutes: Int get() = done?.durationMinutes ?: 0
    val setCount: Int get() = exercises.sumOf { it.done.size }

    /** A session of the soft kind: « Faite, et en version douce ». */
    val soft: Boolean get() = planned?.variant == VARIANT_SOFT
}

private const val VARIANT_SOFT = "soft"

/**
 * The end of the Sport activity (MAG-781): on arrival the session done is written — the sets are kept even if the owner
 * leaves — and its streak read back; « Enregistrer » adds the difficulty and how each exercise felt, clears the
 * activity status and hands the session to Health Connect.
 */
class SportEndViewModel(
    private val statusRepository: ActivityStatusRepository,
    private val workoutRepository: WorkoutRepository,
    private val healthSync: HealthConnectSync,
    private val clock: () -> Instant = Instant::now,
    private val syncScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : ViewModel() {

    private val _uiState = MutableStateFlow(SportEndUiState())
    val uiState: StateFlow<SportEndUiState> = _uiState

    init {
        open()
    }

    fun retry() {
        _uiState.update { SportEndUiState() }
        open()
    }

    private fun open() {
        viewModelScope.launch {
            val status = statusRepository.get(SPORT_ACTIVITY).getOrElse { return@launch fail(SportEndLoad.ERROR) }
            val resume = status?.toSportResume()
            val state = status?.toSportState()
            if (status == null || resume == null || state == null) return@launch fail(SportEndLoad.GONE)
            val planned = workoutRepository.getWorkout(resume.workoutId).getOrElse { return@launch fail(SportEndLoad.ERROR) }
                ?: return@launch fail(SportEndLoad.GONE)

            val existing = state.doneWorkoutId?.let { id -> workoutRepository.getWorkout(id).getOrElse { return@launch fail(SportEndLoad.ERROR) } }
            val done = existing ?: create(planned, state, status.startedAt) ?: return@launch fail(SportEndLoad.ERROR)

            _uiState.update {
                it.copy(
                    load = SportEndLoad.READY,
                    planned = planned,
                    done = done,
                    feelings = done.exercises.map { e -> e.feeling },
                    difficulty = done.difficulty,
                )
            }
            loadDeltas(done)
        }
    }

    /** `POST /api/workouts` once; the id goes to the status first, so a replay finds it. */
    private suspend fun create(planned: Workout, state: SportState, startedAt: String): Workout? {
        val request = SportWorkoutCreate(
            type = WorkoutType.STRENGTH.key,
            title = planned.title,
            startedAt = startedAt,
            durationMinutes = minutesSince(startedAt),
            source = WorkoutSource.ACTIVITY,
            variant = planned.variant,
            exercises = planned.exercises.mapIndexed { i, e -> e.toWrite(state.loadKg.getOrNull(i), state.done.getOrNull(i).orEmpty(), feeling = null) },
        )
        val created = workoutRepository.createSportWorkout(request).getOrNull() ?: return null
        statusRepository.put(SPORT_ACTIVITY, sportOrigin(planned.id), state.copy(doneWorkoutId = created.id).toJson())
        // The rule that keeps the planned session sets the streak after the write: read it back.
        return workoutRepository.getWorkout(created.id).getOrNull() ?: created
    }

    private fun minutesSince(startedAt: String): Int {
        val start = runCatching { OffsetDateTime.parse(startedAt).toInstant() }.getOrNull() ?: return 1
        return Duration.between(start, clock()).toMinutes().coerceIn(1, MAX_MINUTES).toInt()
    }

    // « La dernière fois »: per exercise, the latest other session done that held it; a failed read leaves the gap out.
    private suspend fun loadDeltas(done: Workout) {
        done.exercises.forEach { exercise ->
            val last = workoutRepository.getWorkoutsWithExercise(exercise.exercise).getOrNull()
                ?.firstOrNull { !it.isPlanned && it.id != done.id && it.exercises.any { e -> e.exercise == exercise.exercise && e.done.isNotEmpty() } }
                ?.exercises?.firstOrNull { it.exercise == exercise.exercise && it.done.isNotEmpty() }
            val delta = sportDelta(exercise, last) ?: return@forEach
            _uiState.update { it.copy(deltas = it.deltas + (exercise.exercise to delta)) }
        }
    }

    /** A second touch on the chosen number takes it back. */
    fun chooseDifficulty(value: Int) = _uiState.update { it.copy(difficulty = if (it.difficulty == value) null else value) }

    /** A second touch on the chosen feeling takes it back. */
    fun chooseFeeling(index: Int, feeling: String) = _uiState.update {
        if (index !in it.feelings.indices) return@update it
        it.copy(feelings = it.feelings.mapIndexed { i, f -> if (i == index) feeling.takeIf { new -> new != f } else f })
    }

    fun save() {
        val ui = _uiState.value
        val done = ui.done ?: return
        if (ui.load != SportEndLoad.READY || ui.saving) return
        _uiState.update { it.copy(saving = true, saveFailed = false) }
        viewModelScope.launch {
            val request = SportWorkoutFinish(
                difficulty = ui.difficulty,
                exercises = done.exercises.mapIndexed { i, e -> e.toWrite(e.loadKg, e.done.map { s -> s.toSportDone() }, ui.feelings.getOrNull(i)) },
            )
            workoutRepository.finishSportWorkout(done.id, request).fold(
                onSuccess = {
                    statusRepository.delete(SPORT_ACTIVITY)
                    syncScope.launch { runCatching { healthSync.run() }.rethrowCancellation() }
                    _uiState.update { it.copy(saving = false, saved = true) }
                },
                onFailure = { _ -> _uiState.update { it.copy(saving = false, saveFailed = true) } },
            )
        }
    }

    fun askQuit() = _uiState.update { it.copy(quitAsked = true) }

    fun dismissQuit() = _uiState.update { it.copy(quitAsked = false) }

    private fun fail(load: SportEndLoad) = _uiState.update { it.copy(load = load) }

    private companion object {
        const val MAX_MINUTES = 1440L
    }
}

private fun WorkoutSetDone.toSportDone() = SportSetDone(reps, seconds, meters, loadKg)

private fun WorkoutExercise.toWrite(loadKg: Double?, sets: List<SportSetDone>, feeling: String?) = WorkoutExerciseWrite(
    exercise = exercise,
    step = step.coerceAtLeast(1),
    sets = this.sets,
    target = target,
    targetMin = targetMin ?: target,
    targetMax = targetMax ?: target,
    restSeconds = restSeconds ?: 0,
    loadKg = loadKg,
    tempo = tempo,
    targetRir = targetRir,
    cue = cue,
    feeling = feeling,
    done = sets.map { WorkoutSetWrite(it.reps, it.seconds, it.meters, it.loadKg) },
)

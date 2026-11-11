package com.maggie.app.ui.screens.health.sport

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.model.Workout
import com.maggie.app.data.repository.ActivityStatusRepository
import com.maggie.app.data.repository.WorkoutRepository
import com.maggie.app.ui.navigation.DeepLinks
import com.maggie.app.ui.screens.health.formatDose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
    /** 0-based: the exercise on screen. */
    val exerciseIndex: Int = 0,
    /** Exercise slug → « 3 × 4 »: what the last session done that held it says. */
    val lastTimes: Map<String, String> = emptyMap(),
    /** « Quitter la séance ? » is asked. */
    val quitAsked: Boolean = false,
) {
    val exercises get() = workout?.exercises.orEmpty()
    val current get() = exercises.getOrNull(exerciseIndex)
    val count: Int get() = exercises.size
}

/**
 * The Sport activity (MAG-779): opens the session it is given — by its id, or by the agenda event it is filed under —,
 * writes the starting `activity-status` unless one already holds this session, and resumes at its exercise.
 */
class SportActivityViewModel(
    savedState: SavedStateHandle,
    private val workoutRepository: WorkoutRepository,
    private val statusRepository: ActivityStatusRepository,
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
            val resume = status?.toSportResume()?.takeIf { it.workoutId == workout.id && it.exerciseCount == workout.exercises.size }
            if (resume == null) statusRepository.put(SPORT_ACTIVITY, sportOrigin(workout.id), startState(workout))

            val index = resume?.exerciseIndex ?: 0
            _uiState.update { it.copy(load = SportLoad.READY, workout = workout, exerciseIndex = index) }
            loadLastTimes(workout)
        }
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
        /** The path segment of a launch by event, where the session's id is not known yet. */
        const val NO_WORKOUT = "-"
    }
}

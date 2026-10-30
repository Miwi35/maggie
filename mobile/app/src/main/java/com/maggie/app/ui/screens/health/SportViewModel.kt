package com.maggie.app.ui.screens.health

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.api.WorkoutWriteRequest
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.mercure.MercureTopics
import com.maggie.app.data.mercure.coalescedAndRechecked
import com.maggie.app.data.model.Workout
import com.maggie.app.data.repository.WorkoutRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

enum class WorkoutField { TITLE, DURATION, DISTANCE, KCAL, HEART_RATE }

/** What the full-screen form holds while it is filled: the numbers as typed, the distance in km with a comma or a dot. */
data class WorkoutDraft(
    val type: WorkoutType = WorkoutType.RUN,
    val title: String = "",
    val date: LocalDate,
    val time: LocalTime,
    val duration: String = "",
    val distance: String = "",
    val kcal: String = "",
    val heartRate: String = "",
    val feeling: WorkoutFeeling? = null,
    val notes: String = "",
) {
    private fun optionalInt(text: String, range: IntRange): Result<Int?> {
        if (text.isBlank()) return Result.success(null)
        val value = text.trim().toIntOrNull()?.takeIf { it in range }
        return if (value == null) Result.failure(IllegalArgumentException(text)) else Result.success(value)
    }

    private val durationMinutes: Int? get() = duration.trim().toIntOrNull()?.takeIf { it in DURATION_RANGE }

    private val distanceResult: Result<Int?>
        get() = if (distance.isBlank()) Result.success(null) else {
            parseDistanceMeters(distance)?.takeIf { it in DISTANCE_RANGE }
                ?.let { Result.success<Int?>(it) } ?: Result.failure(IllegalArgumentException(distance))
        }

    /** The API's rules, checked before the request, said in French. */
    fun errors(): Map<WorkoutField, String> = buildMap {
        if (title.isBlank()) put(WorkoutField.TITLE, "Donnez un titre à la séance.")
        if (durationMinutes == null) put(WorkoutField.DURATION, "Indiquez une durée de 1 à $MAX_DURATION minutes.")
        if (distanceResult.isFailure) put(WorkoutField.DISTANCE, "Indiquez une distance de 0 à 1 000 km.")
        if (optionalInt(kcal, KCAL_RANGE).isFailure) put(WorkoutField.KCAL, "Indiquez des calories de 0 à 20 000.")
        if (optionalInt(heartRate, HEART_RATE_RANGE).isFailure) {
            put(WorkoutField.HEART_RATE, "Indiquez une fréquence cardiaque de ${HEART_RATE_RANGE.first} à ${HEART_RATE_RANGE.last} bpm.")
        }
    }

    fun toRequest(zone: java.time.ZoneId): WorkoutWriteRequest? {
        if (errors().isNotEmpty()) return null
        return WorkoutWriteRequest(
            type = type.key,
            title = title.trim(),
            startedAt = ZonedDateTime.of(date, time, zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            durationMinutes = durationMinutes ?: return null,
            distanceMeters = distanceResult.getOrNull(),
            kcal = optionalInt(kcal, KCAL_RANGE).getOrNull(),
            avgHeartRate = optionalInt(heartRate, HEART_RATE_RANGE).getOrNull(),
            feeling = feeling?.key,
            notes = notes.trim().ifEmpty { null },
        )
    }

    companion object {
        const val MAX_DURATION = 1440
        val DURATION_RANGE = 1..MAX_DURATION
        val DISTANCE_RANGE = 0..1_000_000
        val KCAL_RANGE = 0..20_000
        val HEART_RATE_RANGE = 20..250

        fun of(workout: Workout, zone: java.time.ZoneId): WorkoutDraft {
            val start = workoutStart(workout, zone)
            return WorkoutDraft(
                type = WorkoutType.of(workout.type),
                title = workout.title,
                date = start?.toLocalDate() ?: LocalDate.now(zone),
                time = start?.toLocalTime()?.truncatedTo(ChronoUnit.MINUTES) ?: LocalTime.of(18, 0),
                duration = workout.durationMinutes.toString(),
                distance = workout.distanceMeters?.let(::distanceInput).orEmpty(),
                kcal = workout.kcal?.toString().orEmpty(),
                heartRate = workout.avgHeartRate?.toString().orEmpty(),
                feeling = WorkoutFeeling.of(workout.feeling),
                notes = workout.notes.orEmpty(),
            )
        }
    }
}

/** The form on screen: a new session when [workoutId] is null. [done] tells the screen to close. */
data class WorkoutForm(
    val workoutId: String?,
    val draft: WorkoutDraft,
    /** Set by the first save: an empty field is not an error while the form is still being filled. */
    val showErrors: Boolean = false,
    val isSaving: Boolean = false,
    /** What the server refused, said in French. */
    val error: String? = null,
    val done: Boolean = false,
) {
    val isNew: Boolean get() = workoutId == null
    val errors: Map<WorkoutField, String> get() = if (showErrors) draft.errors() else emptyMap()
}

data class SportUiState(
    /** Every session, newest first, planned ones included: each screen keeps the ones it shows. */
    val workouts: List<Workout> = emptyList(),
    val isLoading: Boolean = false,
    /** False until the first answer: the screen shows a spinner, not the empty state. */
    val hasLoaded: Boolean = false,
    val selectedId: String? = null,
    /** The phone's sheet is open on [selectedId]; the tablet's pane is always there. */
    val detailOpen: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val form: WorkoutForm? = null,
) {
    val selected: Workout? get() = workouts.firstOrNull { it.id == selectedId }
}

/**
 * « Santé › Sport » (MAG-705): the training sessions, the detail of the one selected, and the full-screen form that
 * creates, edits and deletes one. The navigation scopes it to the list's entry; the form is what is in front of it.
 * The [clock] says today and the time zone the week and the dates are read in.
 */
class SportViewModel(
    private val repository: WorkoutRepository,
    private val mercureService: MercureService,
    private val authRepository: AuthRepository,
    val clock: Clock = Clock.systemDefaultZone(),
) : ViewModel() {

    private val _uiState = MutableStateFlow(SportUiState(isLoading = true))
    val uiState: StateFlow<SportUiState> = _uiState

    val zone get() = clock.zone

    init {
        refresh()
        subscribeToMercure()
    }

    // A session written elsewhere (the admin, Maggie, the watch) reaches the screen. Read twice, as the other lists do.
    private fun subscribeToMercure() {
        viewModelScope.launch {
            val userId = authRepository.getUserId() ?: return@launch
            mercureService.subscribe(MercureTopics.userScoped(userId, MercureTopics.WORKOUTS))
                .catch { /* MercureService reconnects by itself */ }
                .coalescedAndRechecked()
                .collect { load(showLoading = false) }
        }
    }

    fun refresh() {
        viewModelScope.launch { load(showLoading = true) }
    }

    private suspend fun load(showLoading: Boolean) {
        if (showLoading) _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        repository.getWorkouts()
            .onSuccess { fresh ->
                val sorted = fresh.sortedByDescending(::startInstant)
                val state = _uiState.value
                val gone = state.selectedId != null && sorted.none { it.id == state.selectedId }
                _uiState.value = state.copy(
                    workouts = sorted,
                    isLoading = false,
                    hasLoaded = true,
                    selectedId = if (gone) null else state.selectedId,
                    detailOpen = state.detailOpen && !gone,
                )
            }
            .onFailure { _uiState.value = _uiState.value.copy(isLoading = false, error = MESSAGE_LOAD) }
    }

    private fun startInstant(workout: Workout): Instant =
        runCatching { OffsetDateTime.parse(workout.startedAt).toInstant() }.getOrDefault(Instant.MIN)

    /** A tap on a row: the phone opens the sheet on it, the tablet's pane shows it. */
    fun select(id: String) {
        if (_uiState.value.workouts.none { it.id == id }) return
        _uiState.value = _uiState.value.copy(selectedId = id, detailOpen = true)
    }

    fun closeDetail() {
        _uiState.value = _uiState.value.copy(detailOpen = false)
    }

    fun openNew() {
        val now = ZonedDateTime.now(clock)
        val draft = WorkoutDraft(date = now.toLocalDate(), time = now.toLocalTime().truncatedTo(ChronoUnit.MINUTES))
        _uiState.value = _uiState.value.copy(form = WorkoutForm(workoutId = null, draft = draft), error = null)
    }

    fun openEdit(id: String) {
        val workout = _uiState.value.workouts.firstOrNull { it.id == id }
        if (workout == null) {
            _uiState.value = _uiState.value.copy(form = null, error = MESSAGE_NOT_FOUND)
            return
        }
        _uiState.value = _uiState.value.copy(
            form = WorkoutForm(workoutId = id, draft = WorkoutDraft.of(workout, zone)),
            detailOpen = false,
            error = null,
        )
    }

    fun editDraft(transform: (WorkoutDraft) -> WorkoutDraft) {
        val form = _uiState.value.form ?: return
        _uiState.value = _uiState.value.copy(form = form.copy(draft = transform(form.draft), error = null))
    }

    fun closeForm() {
        _uiState.value = _uiState.value.copy(form = null)
    }

    fun save() {
        val form = _uiState.value.form ?: return
        if (form.isSaving) return
        val request = form.draft.toRequest(zone)
        if (request == null) {
            _uiState.value = _uiState.value.copy(form = form.copy(showErrors = true))
            return
        }

        _uiState.value = _uiState.value.copy(form = form.copy(showErrors = true, isSaving = true, error = null))
        viewModelScope.launch {
            val id = form.workoutId
            val result = if (id == null) repository.createWorkout(request) else repository.updateWorkout(id, request)
            result
                .onSuccess { saved ->
                    _uiState.value = _uiState.value.copy(
                        form = _uiState.value.form?.copy(isSaving = false, done = true),
                        message = if (id == null) "Séance ajoutée" else "Séance enregistrée",
                        selectedId = saved.id,
                    )
                    load(showLoading = false)
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(form = _uiState.value.form?.copy(isSaving = false, error = MESSAGE_SAVE))
                }
        }
    }

    /** Takes the session out of the list at once; it comes back with the reason if the API refuses. */
    fun delete(id: String) {
        val before = _uiState.value
        if (before.workouts.none { it.id == id }) return
        _uiState.value = before.copy(
            workouts = before.workouts.filter { it.id != id },
            selectedId = before.selectedId.takeIf { it != id },
            detailOpen = false,
            error = null,
        )
        viewModelScope.launch {
            repository.deleteWorkout(id)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(message = "Séance supprimée")
                    load(showLoading = false)
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(workouts = before.workouts, selectedId = before.selectedId, error = MESSAGE_DELETE)
                }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }

    companion object {
        const val MESSAGE_LOAD = "Les séances n'ont pas pu être chargées."
        const val MESSAGE_NOT_FOUND = "Cette séance n'existe plus."
        const val MESSAGE_SAVE = "La séance n'a pas été enregistrée."
        const val MESSAGE_DELETE = "La séance n'a pas été supprimée."
    }
}

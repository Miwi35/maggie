package com.maggie.app.ui.screens.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.mercure.MercureTopics
import com.maggie.app.data.mercure.coalescedAndRechecked
import com.maggie.app.data.model.Routine
import com.maggie.app.data.model.RoutineRecurrence
import com.maggie.app.data.repository.RoutineRepository
import com.maggie.app.data.repository.TaskRepository
import com.maggie.app.data.reward.RewardFeedback
import com.maggie.app.util.DateRanges
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate

/**
 * What the sheet edits: a new routine ([id] null) or an existing one. [anchorTime] is kept even
 * when the routine is anchored to a moment, so going back to « À une heure » finds it again; it
 * is only sent with the `time` anchor. [weekday] and [monthDay] likewise wait for their kind.
 */
data class RoutineDraft(
    val id: String? = null,
    val title: String = "",
    val anchor: String = Routine.ANCHOR_WAKE,
    val anchorTime: String = DEFAULT_TIME,
    val kind: String = RoutineRecurrence.DAILY,
    val weekday: Int = 1,
    val monthDay: Int = 1,
    val active: Boolean = true,
    val remind: Boolean = false,
) {
    val isNew: Boolean get() = id == null

    val canSave: Boolean get() = title.isNotBlank()

    private val sentAnchorTime: String? get() = anchorTime.takeIf { anchor == Routine.ANCHOR_TIME }

    private val recurrence: RoutineRecurrence
        get() = RoutineRecurrence(
            kind = kind,
            weekday = weekday.takeIf { kind == RoutineRecurrence.WEEKLY },
            monthDay = monthDay.takeIf { kind == RoutineRecurrence.MONTHLY },
        )

    /** The body of `POST /api/routines`: every field. */
    fun toCreateBody(): JsonObject = buildJsonObject {
        put("title", title.trim())
        put("anchor", anchor)
        put("anchorTime", sentAnchorTime)
        put("recurrence", recurrenceJson(recurrence))
        put("active", active)
        put("remind", remind)
    }

    /** The body of `PATCH /api/routines/{id}`: only what differs from [routine]; empty when nothing does. */
    fun changesFrom(routine: Routine): JsonObject = buildJsonObject {
        if (title.trim() != routine.title) put("title", title.trim())
        // One decides whether the other is allowed: the API reads them together.
        if (anchor != routine.anchor || sentAnchorTime != routine.anchorTime) {
            put("anchor", anchor)
            put("anchorTime", sentAnchorTime)
        }
        if (recurrence != routine.recurrence) put("recurrence", recurrenceJson(recurrence))
        if (active != routine.active) put("active", active)
        if (remind != routine.remind) put("remind", remind)
    }

    companion object {
        const val DEFAULT_TIME = "08:00"

        /** A new routine: every day at waking; « Chaque {jour} » and « Chaque mois » start on today. */
        fun new(today: LocalDate): RoutineDraft = RoutineDraft(weekday = today.dayOfWeek.value, monthDay = today.dayOfMonth)

        fun of(routine: Routine, today: LocalDate): RoutineDraft = RoutineDraft(
            id = routine.id,
            title = routine.title,
            anchor = routine.anchor,
            anchorTime = routine.anchorTime ?: routine.time,
            kind = routine.recurrence.kind,
            weekday = routine.recurrence.weekday ?: today.dayOfWeek.value,
            monthDay = routine.recurrence.monthDay ?: today.dayOfMonth,
            active = routine.active,
            remind = routine.remind,
        )

        private fun recurrenceJson(recurrence: RoutineRecurrence): JsonObject = buildJsonObject {
            put("kind", JsonPrimitive(recurrence.kind))
            recurrence.weekday?.let { put("weekday", it) }
            recurrence.monthDay?.let { put("monthDay", it) }
        }
    }
}

data class RoutinesUiState(
    /** In the API's order: by hour, then title. */
    val routines: List<Routine> = emptyList(),
    val isLoading: Boolean = false,
    /** The list could not be read and there is nothing to show. */
    val error: String? = null,
    /** A gesture failed over a list still shown. */
    val message: String? = null,
    /** The sheet, open on this draft; null when closed. */
    val draft: RoutineDraft? = null,
    val isSaving: Boolean = false,
    /** Routines whose tick is in flight: a second tap waits for the first. */
    val pending: Set<String> = emptySet(),
) {
    val hasContent: Boolean get() = routines.isNotEmpty()
}

/**
 * The « Routines » tab of the tasks (MAG-511): every routine in its group, today's tick on each
 * one due, and the sheet that creates, changes, pauses or deletes one. A tick is shown at once
 * and replaced by the routine the API answers; refused, it is taken back. The push of a routine
 * or of a tick changed elsewhere (the admin, Maggie, the day's view) reads the list again.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoutinesViewModel(
    private val routineRepository: RoutineRepository,
    private val taskRepository: TaskRepository,
    private val feedback: RewardFeedback,
    private val mercureService: MercureService,
    private val authRepository: AuthRepository,
    private val today: () -> LocalDate = { DateRanges.todayDate() },
) : ViewModel() {

    private val _uiState = MutableStateFlow(RoutinesUiState())
    val uiState: StateFlow<RoutinesUiState> = _uiState

    private var loadJob: Job? = null
    private var listenJob: Job? = null

    /** The tab came on: the list is read again and the pushes listened to, only while it is on. */
    fun onOpened() {
        listen()
        load()
    }

    fun onClosed() {
        listenJob?.cancel()
        listenJob = null
    }

    fun refresh() = load()

    fun dismissMessage() {
        _uiState.update { it.copy(message = null) }
    }

    private fun listen() {
        if (listenJob?.isActive == true) return
        listenJob = viewModelScope.launch {
            authRepository.token
                .map { it != null }
                .distinctUntilChanged()
                .collectLatest { authenticated ->
                    if (!authenticated) return@collectLatest
                    val userId = authRepository.getUserId() ?: return@collectLatest
                    merge(
                        mercureService.subscribe(MercureTopics.userScoped(userId, MercureTopics.ROUTINES))
                            .catch { /* SSE reconnects automatically */ },
                        mercureService.subscribe(MercureTopics.userScoped(userId, MercureTopics.ROUTINE_CHECKS))
                            .catch { /* SSE reconnects automatically */ },
                    )
                        .coalescedAndRechecked()
                        .collect { load() }
                }
        }
    }

    private fun load() {
        loadJob?.cancel()
        _uiState.update { it.copy(isLoading = true, error = null) }
        loadJob = viewModelScope.launch {
            routineRepository.getRoutines()
                .onSuccess { routines -> _uiState.update { it.copy(routines = routines, isLoading = false, error = null) } }
                .onFailure { e ->
                    val text = e.message ?: "Impossible de charger les routines."
                    _uiState.update {
                        if (it.hasContent) it.copy(isLoading = false, message = text) else it.copy(isLoading = false, error = text)
                    }
                }
        }
    }

    /** Ticks today, or unticks it; a short buzz on a tick when the user lets rewards vibrate. */
    fun toggleCheck(routine: Routine) {
        val state = _uiState.value
        if (routine.id in state.pending) return
        val current = state.routines.firstOrNull { it.id == routine.id } ?: routine
        if (!current.dueToday) return
        val checked = !current.checkedToday
        _uiState.update { s ->
            s.copy(
                routines = s.routines.map { if (it.id == current.id) it.withCheckedToday(checked) else it },
                pending = s.pending + current.id,
                message = null,
            )
        }
        viewModelScope.launch {
            routineRepository.setChecked(current.id, today(), checked)
                .onSuccess { answered ->
                    _uiState.update { s ->
                        s.copy(routines = s.routines.map { if (it.id == answered.id) answered else it }, pending = s.pending - current.id)
                    }
                    if (checked && taskRepository.getTaskSettings().getOrNull()?.rewardVibration == true) feedback.vibrate()
                }
                .onFailure { e ->
                    _uiState.update { s ->
                        s.copy(
                            routines = s.routines.map { if (it.id == current.id) it.withCheckedToday(!checked) else it },
                            pending = s.pending - current.id,
                            message = e.message ?: "La coche n'a pas été enregistrée.",
                        )
                    }
                }
        }
    }

    /** « Nouvelle routine ». */
    fun openNew() {
        _uiState.update { it.copy(draft = RoutineDraft.new(today()), message = null) }
    }

    /** A card touched: the same sheet, on that routine. */
    fun openEdit(routine: Routine) {
        _uiState.update { it.copy(draft = RoutineDraft.of(routine, today()), message = null) }
    }

    fun updateDraft(draft: RoutineDraft) {
        _uiState.update { if (it.draft == null) it else it.copy(draft = draft) }
    }

    fun closeEditor() {
        _uiState.update { it.copy(draft = null, isSaving = false) }
    }

    /** Creates the draft, or sends what changed in it; the sheet closes on the API's answer. */
    fun save() {
        val state = _uiState.value
        val draft = state.draft ?: return
        if (!draft.canSave || state.isSaving) return
        val original = draft.id?.let { id -> state.routines.firstOrNull { it.id == id } }
        val changes = original?.let(draft::changesFrom)
        if (original != null && changes.isNullOrEmpty()) {
            closeEditor()
            return
        }
        _uiState.update { it.copy(isSaving = true, message = null) }
        viewModelScope.launch {
            val result = if (original == null) {
                routineRepository.createRoutine(draft.toCreateBody())
            } else {
                routineRepository.updateRoutine(original.id, changes!!)
            }
            result
                .onSuccess { saved ->
                    _uiState.update { s ->
                        val routines = if (s.routines.any { it.id == saved.id }) {
                            s.routines.map { if (it.id == saved.id) saved else it }
                        } else {
                            s.routines + saved
                        }
                        s.copy(routines = routines, draft = null, isSaving = false)
                    }
                    // The API's order (hour, then title) is read back rather than guessed.
                    load()
                }
                .onFailure { e -> _uiState.update { it.copy(isSaving = false, message = e.message ?: "La routine n'a pas été enregistrée.") } }
        }
    }

    /** « Supprimer », once confirmed: the routine and its ticks. */
    fun delete() {
        val state = _uiState.value
        val id = state.draft?.id ?: return
        if (state.isSaving) return
        _uiState.update { it.copy(isSaving = true, message = null) }
        viewModelScope.launch {
            routineRepository.deleteRoutine(id)
                .onSuccess {
                    _uiState.update { s -> s.copy(routines = s.routines.filterNot { it.id == id }, draft = null, isSaving = false) }
                }
                .onFailure { e -> _uiState.update { it.copy(isSaving = false, message = e.message ?: "La routine n'a pas été supprimée.") } }
        }
    }
}

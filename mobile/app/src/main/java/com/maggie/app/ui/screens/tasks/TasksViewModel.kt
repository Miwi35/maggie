package com.maggie.app.ui.screens.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.api.TaskCreateRequest
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.mercure.MercureTopics
import com.maggie.app.data.mercure.coalescedAndRechecked
import com.maggie.app.data.model.DayPlan
import com.maggie.app.data.model.DayProposal
import com.maggie.app.data.model.DayReview
import com.maggie.app.data.model.DayTask
import com.maggie.app.data.model.Task
import com.maggie.app.data.model.TaskView
import com.maggie.app.data.model.TaskViewCounts
import com.maggie.app.data.repository.TaskRepository
import com.maggie.app.util.DateRanges
import com.maggie.app.util.rethrowCancellation
import kotlinx.coroutines.Job
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

data class TasksUiState(
    val view: TaskView = TaskView.TODAY,
    /** The tasks of [view] in the API's order, each subtask after its parent. */
    val tasks: List<Task> = emptyList(),
    /** What each tab holds, refreshed by every read; `null` until the first one answered. */
    val counts: TaskViewCounts? = null,
    /** Size of the whole [view]: « Faites » is paged, so [tasks] can hold less. */
    val totalItems: Int = 0,
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    /** The read failed. */
    val error: String? = null,
    /** An action (tick, add) failed; the list is left as it was. */
    val message: String? = null,
    /** The quick-add field. */
    val draft: String = "",
    val isAdding: Boolean = false,
    /** The last page of « Faites » read. */
    val page: Int = 1,
    /** « Aujourd'hui » is the day (MAG-497): five slots at most, « Maintenant » and the reserve. */
    val day: DayPlan? = null,
    /** The reserve is folded away until asked for: five is what the day shows. */
    val isReserveOpen: Boolean = false,
    /** A change of the day is in flight; another waits for its answer, the list it sends being whole. */
    val isSavingDay: Boolean = false,
    /** A task asked for from the day, read whole: the screen hands it to the detail and clears it. */
    val opened: Task? = null,
    /** « Ta journée » (MAG-498): the morning's proposal on screen instead of the day; `null` when closed. */
    val morning: MorningState? = null,
    /** « Le soir » (MAG-500): the review on screen instead of the day; `null` when closed. */
    val evening: EveningState? = null,
) {
    /** What the open tab draws has arrived: the day in « Aujourd'hui », the list elsewhere. */
    val hasContent: Boolean get() = if (view == TaskView.TODAY) day != null else tasks.isNotEmpty()

    /** The day is never empty: its add field and its reserve are always there. */
    val isEmpty: Boolean get() = !isLoading && error == null && view != TaskView.TODAY && tasks.isEmpty()

    /** Only « Faites » is paged: more is out there when the page held back some of the view. */
    val hasMore: Boolean get() = view == TaskView.DONE && tasks.size < totalItems
}

/** The morning's five being prepared: [proposal] is `null` while it is read. */
data class MorningState(
    val proposal: DayProposal? = null,
    /** « C'est parti » is in flight. */
    val isConfirming: Boolean = false,
)

/** The three ways out of a task left at night, each one gesture (MAG-500). */
enum class EveningOutcome(val label: String) {
    /** Written in tomorrow's five, without settling that day: the morning keeps it. */
    TOMORROW("Demain"),

    /** Left in the reserve; it comes back « reportée ». */
    LATER("Plus tard"),

    /** No due day, out of today: it waits in « Un jour ». « Annuler » puts it back. */
    ASIDE("Mettre de côté"),
}

/**
 * The evening's review: [day] as it stood when the review opened — a task set aside leaves today,
 * not the review. [todayIds] and [tomorrowIds] are the two days as written since; [tomorrowIds] is
 * `null` while tomorrow is read.
 */
data class EveningState(
    val day: DayPlan,
    val todayIds: List<String> = day.taskIds,
    val tomorrowIds: List<String>? = null,
    val outcomes: Map<String, EveningOutcome> = emptyMap(),
    val helped: Set<String> = emptySet(),
    /** A gesture or « Bonne nuit » is in flight. */
    val isSaving: Boolean = false,
) {
    val done: List<DayTask> get() = day.slots.map { it.task }.filter { it.isDone }

    val left: List<DayTask> get() = day.slots.map { it.task }.filterNot { it.isDone }

    fun outcomeOf(taskId: String): EveningOutcome = outcomes[taskId] ?: EveningOutcome.LATER

    /** Tomorrow already holds five others: « Demain » is refused, the way out stays « Plus tard ». */
    fun isTomorrowFull(taskId: String): Boolean =
        tomorrowIds != null && tomorrowIds.size >= DayPlan.MAX_SLOTS && taskId !in tomorrowIds

    /** « Demain » waits for tomorrow to be read, and is refused once it holds five others. */
    fun canGoTomorrow(taskId: String): Boolean = tomorrowIds != null && !isTomorrowFull(taskId)
}

/** [ids] with [id] back where it stood in [original], after the last one before it still there. */
internal fun restoreAt(ids: List<String>, id: String, original: List<String>): List<String> {
    if (id in ids) return ids
    val before = original.takeWhile { it != id }.lastOrNull { it in ids }
    val at = before?.let { ids.indexOf(it) + 1 } ?: 0
    return ids.take(at) + id + ids.drop(at)
}

/**
 * The tasks screen (MAG-408): one of four views at a time, read from `/api/tasks/view/{view}`.
 * The API decides which view a task is in and in which order; this class only asks again after
 * anything that can move a task (a tick, an add, a push).
 *
 * « Aujourd'hui » is the day instead (MAG-497), `/api/day-plans/{today}`: five tasks at most, the
 * rest in a reserve. Its slots are changed by sending the whole list back; the API answers with the
 * day as it stands, which then replaces the one shown.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TasksViewModel(
    private val taskRepository: TaskRepository,
    private val mercureService: MercureService,
    private val authRepository: AuthRepository,
    private val today: () -> LocalDate = { DateRanges.todayDate() },
    private val now: () -> LocalTime = { LocalTime.now() },
) : ViewModel() {

    private val _uiState = MutableStateFlow(TasksUiState())
    val uiState: StateFlow<TasksUiState> = _uiState

    private var loadJob: Job? = null
    private var moreJob: Job? = null
    private var listenJob: Job? = null
    private var proposalJob: Job? = null
    private var tomorrowJob: Job? = null

    /** The morning opens once per run of the app: leaving it unconfirmed does not bring it back. */
    private var morningOffered = false

    /** So does the evening: « Plus tard » does not bring it back before the next run. */
    private var eveningOffered = false

    /**
     * The screen came on: the list is read again, without blanking what is already there, and a
     * task changed elsewhere (Maggie, Google, the admin) moves between views, so the push is listened
     * to. Only while the screen is on: the view model is made with the navigation graph, and a stream
     * kept for good counts against the 32 the app may hold open to the hub — the 33rd, a recipe
     * sheet's, never connected.
     */
    fun onOpened() {
        listen()
        reload()
    }

    /** The screen left: the streams are released. */
    fun onClosed() {
        listenJob?.cancel()
        listenJob = null
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
                    // The day publishes apart from its tasks: a slot changed elsewhere moves no task.
                    merge(
                        mercureService.subscribe(MercureTopics.userScoped(userId, MercureTopics.TASKS))
                            .catch { /* SSE reconnects automatically */ },
                        mercureService.subscribe(MercureTopics.userScoped(userId, MercureTopics.DAY_PLANS))
                            .catch { /* SSE reconnects automatically */ },
                    )
                        .coalescedAndRechecked()
                        .collect { reload() }
                }
        }
    }

    /** Pull-to-refresh. */
    fun refresh() = reload()

    fun selectView(view: TaskView) {
        if (view == _uiState.value.view) return
        // The other tab's tasks must not stay under this one's label while the read is in flight.
        _uiState.update { it.copy(view = view, tasks = emptyList(), totalItems = 0, message = null, page = 1) }
        load(spinner = true)
    }

    fun onDraftChange(draft: String) {
        _uiState.update { it.copy(draft = draft) }
    }

    /**
     * Adds the draft with the tab's default day (today, tomorrow, none) and reads the view again.
     * Added from the day, it takes the next slot while one is free, else it waits in the reserve.
     */
    fun addTask() {
        val state = _uiState.value
        val title = state.draft.trim()
        if (title.isEmpty() || state.isAdding || !state.view.addable) return
        val request = TaskCreateRequest(title = title, dueDate = state.view.defaultDueDate(today()))
        _uiState.update { it.copy(isAdding = true, message = null) }
        viewModelScope.launch {
            taskRepository.createTask(request)
                .onSuccess { created ->
                    _uiState.update { it.copy(isAdding = false, draft = "") }
                    val day = _uiState.value.day
                    if (state.view == TaskView.TODAY && day != null && !day.isFull) {
                        taskRepository.setDaySlots(day.day, day.taskIds + created.id)
                            .onSuccess { saved -> _uiState.update { it.copy(day = saved) } }
                            .onFailure { _uiState.update { it.copy(message = "La tâche attend dans la réserve.") } }
                    }
                    reload()
                }
                .onFailure {
                    _uiState.update { it.copy(isAdding = false, message = "Impossible d'ajouter la tâche.") }
                }
        }
    }

    /** Ticks a task, or unticks it; the view is read again since the task changes view. */
    fun toggleDone(task: Task) {
        viewModelScope.launch {
            taskRepository.toggleDone(task.id, !task.isDone)
                .onSuccess { reload() }
                .onFailure { _uiState.update { it.copy(message = "Impossible de modifier la tâche.") } }
        }
    }

    /**
     * Ticks a task of the day, or unticks it. The day moves at once — « Maintenant » goes to the next
     * slot, the ring fills — and is read again once the server took it; a refusal puts it back.
     */
    fun toggleDayTask(task: DayTask) {
        val before = _uiState.value.day ?: return
        val done = !task.isDone
        _uiState.update {
            it.copy(day = before.withCompleted(task.id, if (done) Instant.now().toString() else null), message = null)
        }
        viewModelScope.launch {
            taskRepository.toggleDone(task.id, done)
                .onSuccess { reload() }
                .onFailure { _uiState.update { it.copy(day = before, message = "Impossible de modifier la tâche.") } }
        }
    }

    fun toggleReserve() {
        _uiState.update { it.copy(isReserveOpen = !it.isReserveOpen) }
    }

    /**
     * Puts a reserve task in the day: in the next free slot, or in place of [insteadOf] when the five
     * are taken — that one goes back to the reserve. A full day and no slot named changes nothing.
     */
    fun putInDay(taskId: String, insteadOf: String? = null) {
        val day = _uiState.value.day ?: return
        val ids = day.taskIds
        val next = when {
            taskId in ids -> return
            insteadOf != null -> ids.map { if (it == insteadOf) taskId else it }.takeIf { insteadOf in ids } ?: return
            !day.isFull -> ids + taskId
            else -> return
        }
        saveDay(day, next)
    }

    /** « Remettre dans la réserve »: the slot is freed, the ones after it move up. */
    fun removeFromDay(taskId: String) {
        val day = _uiState.value.day ?: return
        if (taskId !in day.taskIds) return
        saveDay(day, day.taskIds - taskId)
    }

    /** The day shown is the one written, even when the screen stayed open past midnight. */
    private fun saveDay(day: DayPlan, taskIds: List<String>) {
        if (_uiState.value.isSavingDay) return
        _uiState.update { it.copy(isSavingDay = true, message = null) }
        viewModelScope.launch {
            taskRepository.setDaySlots(day.day, taskIds)
                .onSuccess { saved -> _uiState.update { it.copy(day = saved, isSavingDay = false) } }
                // The API's reason is written for Maggie, in English: the owner reads ours.
                .onFailure { _uiState.update { it.copy(isSavingDay = false, message = "Impossible de modifier la journée.") } }
        }
    }

    /** « Changer » on the day: the morning's proposal, read now. */
    fun openMorning() {
        if (_uiState.value.morning != null) return
        _uiState.update { it.copy(morning = MorningState(), message = null) }
        proposalJob?.cancel()
        proposalJob = viewModelScope.launch {
            taskRepository.getDayProposal(today())
                .onSuccess { proposal -> _uiState.update { s -> s.copy(morning = s.morning?.copy(proposal = proposal)) } }
                .onFailure { _uiState.update { it.copy(morning = null, message = "Impossible de proposer la journée.") } }
        }
    }

    /** Slot [index] of the proposal takes the next task of the reserve; nothing is written. */
    fun swapMorning(index: Int) {
        _uiState.update { s ->
            val morning = s.morning ?: return@update s
            val proposal = morning.proposal ?: return@update s
            if (morning.isConfirming) s else s.copy(morning = morning.copy(proposal = proposal.swap(index)))
        }
    }

    /** « C'est parti »: the day is written with the five kept, which confirms it. */
    fun confirmMorning() {
        val morning = _uiState.value.morning ?: return
        val proposal = morning.proposal ?: return
        if (morning.isConfirming || proposal.slots.isEmpty()) return
        _uiState.update { it.copy(morning = morning.copy(isConfirming = true), message = null) }
        viewModelScope.launch {
            taskRepository.setDaySlots(proposal.day, proposal.taskIds)
                .onSuccess { saved -> _uiState.update { it.copy(day = saved, morning = null) } }
                .onFailure {
                    _uiState.update { it.copy(morning = morning.copy(isConfirming = false), message = "Impossible d'enregistrer la journée.") }
                }
        }
    }

    /** « Plus tard »: the day stays as it is. */
    fun dismissMorning() {
        proposalJob?.cancel()
        _uiState.update { it.copy(morning = null) }
    }

    /**
     * Whether the morning opens by itself: the first time the day is read after the morning time while
     * it has no `confirmedAt`. Nothing opens when the morning time cannot be read. Asked before the day
     * is shown, so the day does not flash before the morning replaces it.
     */
    private suspend fun morningDue(day: DayPlan): Boolean {
        if (morningOffered || day.confirmedAt != null || _uiState.value.morning != null) return false
        val morningTime = taskRepository.getMorningTime().getOrNull()?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
            ?: return false
        if (now() < morningTime) return false
        morningOffered = true
        return true
    }

    /** « Le soir » on the day, or by itself after the evening time: the day as it stands, tomorrow read now. */
    fun openEvening() {
        val day = _uiState.value.day ?: return
        if (_uiState.value.evening != null || day.total == 0) return
        _uiState.update { it.copy(evening = EveningState(day = day), message = null) }
        tomorrowJob?.cancel()
        tomorrowJob = viewModelScope.launch {
            taskRepository.getDayPlan(today().plusDays(1)).onSuccess { tomorrow ->
                _uiState.update { s ->
                    val evening = s.evening ?: return@update s
                    // A task already in tomorrow's five comes out that way.
                    val planned = tomorrow.taskIds.filter { id -> evening.left.any { it.id == id } }
                    s.copy(
                        evening = evening.copy(
                            tomorrowIds = tomorrow.taskIds,
                            outcomes = planned.associateWith { EveningOutcome.TOMORROW } + evening.outcomes,
                        ),
                    )
                }
            }
        }
    }

    /**
     * The way out of [taskId], written at once; the previous one is undone first, so a task is in one
     * place at a time. « Demain » beyond five is refused and the task stays « Plus tard »; « Plus tard »
     * after « Mettre de côté » is « Annuler »: the due day and the slot come back.
     */
    fun chooseOutcome(taskId: String, outcome: EveningOutcome) {
        val evening = _uiState.value.evening ?: return
        val task = evening.left.firstOrNull { it.id == taskId } ?: return
        val previous = evening.outcomeOf(taskId)
        if (evening.isSaving || outcome == previous) return
        // Tomorrow is written whole: not before it was read, or its five would be lost.
        val touchesTomorrow = outcome == EveningOutcome.TOMORROW || previous == EveningOutcome.TOMORROW
        if (touchesTomorrow && evening.tomorrowIds == null) return
        if (outcome == EveningOutcome.TOMORROW && evening.isTomorrowFull(taskId)) {
            _uiState.update { it.copy(message = "Demain a déjà ${DayPlan.MAX_SLOTS} tâches.") }
            return
        }
        _uiState.update { it.copy(evening = evening.copy(isSaving = true), message = null) }
        viewModelScope.launch {
            val today = today()
            val tomorrow = today.plusDays(1)
            var todayIds = evening.todayIds
            // Only what this gesture wrote: tomorrow as read stays as it is otherwise.
            var tomorrowIds: List<String>? = null
            val written = runCatching {
                if (previous == EveningOutcome.TOMORROW) {
                    tomorrowIds = taskRepository.setDaySlots(tomorrow, evening.tomorrowIds.orEmpty() - taskId, confirm = false).getOrThrow().taskIds
                }
                if (previous == EveningOutcome.ASIDE) {
                    taskRepository.setDueDate(taskId, task.dueDate).getOrThrow()
                    todayIds = taskRepository.setDaySlots(today, restoreAt(todayIds, taskId, evening.day.taskIds)).getOrThrow().taskIds
                }
                if (outcome == EveningOutcome.TOMORROW) {
                    val before = tomorrowIds ?: evening.tomorrowIds.orEmpty()
                    tomorrowIds = taskRepository.setDaySlots(tomorrow, before - taskId + taskId, confirm = false).getOrThrow().taskIds
                }
                if (outcome == EveningOutcome.ASIDE) {
                    taskRepository.setDueDate(taskId, null).getOrThrow()
                    todayIds = taskRepository.setDaySlots(today, todayIds - taskId).getOrThrow().taskIds
                }
            }.rethrowCancellation()
            _uiState.update { s ->
                val current = s.evening ?: return@update s
                val next = current.copy(todayIds = todayIds, tomorrowIds = tomorrowIds ?: current.tomorrowIds, isSaving = false)
                if (written.isSuccess) {
                    s.copy(evening = next.copy(outcomes = current.outcomes + (taskId to outcome)))
                } else {
                    s.copy(evening = next, message = "Impossible de modifier la tâche.")
                }
            }
        }
    }

    /** « Qu'est-ce qui t'a aidé ? »: [value] ticked or unticked, recorded by « Bonne nuit ». */
    fun toggleHelped(value: String) {
        _uiState.update { s ->
            val evening = s.evening ?: return@update s
            val helped = if (value in evening.helped) evening.helped - value else evening.helped + value
            s.copy(evening = evening.copy(helped = helped))
        }
    }

    /** « Bonne nuit »: what helped is recorded, the review closes and does not open again today. */
    fun goodNight() {
        val evening = _uiState.value.evening ?: return
        if (evening.isSaving) return
        _uiState.update { it.copy(evening = evening.copy(isSaving = true), message = null) }
        val helped = DayReview.HELPED.map { it.first }.filter { it in evening.helped }
        viewModelScope.launch {
            taskRepository.saveDayReview(today(), helped)
                .onSuccess {
                    _uiState.update { it.copy(evening = null) }
                    reload()
                }
                .onFailure {
                    _uiState.update { s -> s.copy(evening = s.evening?.copy(isSaving = false), message = "Impossible d'enregistrer le bilan.") }
                }
        }
    }

    /** « Plus tard »: the review closes without being recorded; what the gestures wrote stays. */
    fun dismissEvening() {
        if (_uiState.value.evening?.isSaving == true) return
        tomorrowJob?.cancel()
        _uiState.update { it.copy(evening = null) }
        reload()
    }

    /**
     * Whether the evening opens by itself: the first time the day is read after the evening time, on a
     * day that holds tasks, while its review is not done. Nothing opens when the evening time or the
     * review cannot be read.
     */
    private suspend fun eveningDue(day: DayPlan): Boolean {
        if (eveningOffered || day.total == 0 || _uiState.value.evening != null) return false
        val eveningTime = taskRepository.getEveningTime().getOrNull()?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
            ?: return false
        if (now() < eveningTime) return false
        if (taskRepository.getDayReview(today()).getOrNull()?.isDone != false) return false
        eveningOffered = true
        return true
    }

    /** The day lists a shorter task: its detail needs the whole one, read before it opens. */
    fun openTask(id: String) {
        viewModelScope.launch {
            taskRepository.fetchTask(id)
                .onSuccess { task -> _uiState.update { it.copy(opened = task) } }
                .onFailure { _uiState.update { it.copy(message = "Impossible d'ouvrir la tâche.") } }
        }
    }

    /** The detail took the task [openTask] read. */
    fun onTaskOpened() {
        _uiState.update { it.copy(opened = null) }
    }

    /** The next page of « Faites », when the user scrolled to the end of what is loaded. */
    fun loadMore() {
        val state = _uiState.value
        if (!state.hasMore || state.isLoading || state.isLoadingMore) return
        val view = state.view
        val page = state.page + 1
        _uiState.update { it.copy(isLoadingMore = true) }
        moreJob = viewModelScope.launch {
            taskRepository.getTaskView(view, page)
                .onSuccess { response ->
                    _uiState.update { current ->
                        val known = current.tasks.map { it.id }.toSet()
                        current.copy(
                            tasks = current.tasks + response.member.filter { it.id !in known },
                            counts = response.counts,
                            totalItems = response.totalItems,
                            isLoadingMore = false,
                            page = page,
                        )
                    }
                }
                .onFailure {
                    _uiState.update { it.copy(isLoadingMore = false, message = "Impossible de charger la suite.") }
                }
        }
    }

    fun dismissMessage() {
        _uiState.update { it.copy(message = null) }
    }

    private fun reload() = load(spinner = !_uiState.value.hasContent)

    private fun load(spinner: Boolean) {
        loadJob?.cancel()
        moreJob?.cancel()
        val view = _uiState.value.view
        _uiState.update { it.copy(isLoading = spinner || it.isLoading, isLoadingMore = false, error = null) }
        loadJob = viewModelScope.launch {
            if (view == TaskView.TODAY) loadDay() else loadView(view)
        }
    }

    /** The day, and beside it « Aujourd'hui »'s view for the counts the other tabs show. */
    private suspend fun loadDay(): Unit = coroutineScope {
        val counts = async { taskRepository.getTaskView(TaskView.TODAY) }
        taskRepository.getDayPlan(today())
            .onSuccess { day ->
                // The evening first: past it, a day never confirmed is reviewed, not prepared.
                val evening = eveningDue(day)
                val morning = !evening && morningDue(day)
                _uiState.update { it.copy(day = day, isLoading = false) }
                if (evening) openEvening() else if (morning) openMorning()
            }
            .onFailure { e -> failed(e, "Impossible de charger la journée.") }
        counts.await().onSuccess { response -> _uiState.update { it.copy(counts = response.counts) } }
    }

    private suspend fun loadView(view: TaskView) {
        taskRepository.getTaskView(view)
            .onSuccess { response ->
                _uiState.update {
                    it.copy(
                        tasks = response.member,
                        counts = response.counts,
                        totalItems = response.totalItems,
                        isLoading = false,
                        page = 1,
                    )
                }
            }
            .onFailure { e -> failed(e, "Impossible de charger les tâches.") }
    }

    /** A refresh that fails under a list keeps the list: the error replaces it only when there is none. */
    private fun failed(e: Throwable, fallback: String) {
        val text = e.message ?: fallback
        _uiState.update {
            if (it.hasContent) it.copy(isLoading = false, message = text)
            else it.copy(isLoading = false, error = text)
        }
    }
}

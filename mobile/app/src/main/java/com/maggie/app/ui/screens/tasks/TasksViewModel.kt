package com.maggie.app.ui.screens.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.api.TaskCreateRequest
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.mercure.MercureTopics
import com.maggie.app.data.mercure.coalescedAndRechecked
import com.maggie.app.data.model.DayPlan
import com.maggie.app.data.model.DayTask
import com.maggie.app.data.model.Task
import com.maggie.app.data.model.TaskView
import com.maggie.app.data.model.TaskViewCounts
import com.maggie.app.data.repository.TaskRepository
import com.maggie.app.util.DateRanges
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
) {
    /** What the open tab draws has arrived: the day in « Aujourd'hui », the list elsewhere. */
    val hasContent: Boolean get() = if (view == TaskView.TODAY) day != null else tasks.isNotEmpty()

    /** The day is never empty: its add field and its reserve are always there. */
    val isEmpty: Boolean get() = !isLoading && error == null && view != TaskView.TODAY && tasks.isEmpty()

    /** Only « Faites » is paged: more is out there when the page held back some of the view. */
    val hasMore: Boolean get() = view == TaskView.DONE && tasks.size < totalItems
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
) : ViewModel() {

    private val _uiState = MutableStateFlow(TasksUiState())
    val uiState: StateFlow<TasksUiState> = _uiState

    private var loadJob: Job? = null
    private var moreJob: Job? = null
    private var listenJob: Job? = null

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
            .onSuccess { day -> _uiState.update { it.copy(day = day, isLoading = false) } }
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

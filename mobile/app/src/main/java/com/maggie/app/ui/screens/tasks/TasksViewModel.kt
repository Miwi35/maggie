package com.maggie.app.ui.screens.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.api.TaskCreateRequest
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.mercure.MercureTopics
import com.maggie.app.data.mercure.coalescedAndRechecked
import com.maggie.app.data.model.Task
import com.maggie.app.data.model.TaskView
import com.maggie.app.data.model.TaskViewCounts
import com.maggie.app.data.repository.TaskRepository
import com.maggie.app.util.DateRanges
import kotlinx.coroutines.Job
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
) {
    val isEmpty: Boolean get() = !isLoading && error == null && tasks.isEmpty()

    /** Only « Faites » is paged: more is out there when the page held back some of the view. */
    val hasMore: Boolean get() = view == TaskView.DONE && tasks.size < totalItems
}

/**
 * The tasks screen (MAG-408): one of four views at a time, read from `/api/tasks/view/{view}`.
 * The API decides which view a task is in and in which order; this class only asks again after
 * anything that can move a task (a tick, an add, a push).
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

    /** The screen left: the stream is released. */
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
                    mercureService.subscribe(MercureTopics.userScoped(userId, MercureTopics.TASKS))
                        .catch { /* SSE reconnects automatically */ }
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

    /** Adds the draft with the tab's default day (today, tomorrow, none) and reads the view again. */
    fun addTask() {
        val state = _uiState.value
        val title = state.draft.trim()
        if (title.isEmpty() || state.isAdding || !state.view.addable) return
        val request = TaskCreateRequest(title = title, dueDate = state.view.defaultDueDate(today()))
        _uiState.update { it.copy(isAdding = true, message = null) }
        viewModelScope.launch {
            taskRepository.createTask(request)
                .onSuccess {
                    _uiState.update { it.copy(isAdding = false, draft = "") }
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

    private fun reload() = load(spinner = _uiState.value.tasks.isEmpty())

    private fun load(spinner: Boolean) {
        loadJob?.cancel()
        moreJob?.cancel()
        val view = _uiState.value.view
        _uiState.update { it.copy(isLoading = spinner || it.isLoading, isLoadingMore = false, error = null) }
        loadJob = viewModelScope.launch {
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
                .onFailure { e ->
                    val text = e.message ?: "Impossible de charger les tâches."
                    // A refresh that fails under a list keeps the list: the error replaces it only when it is empty.
                    _uiState.update {
                        if (it.tasks.isEmpty()) it.copy(isLoading = false, error = text)
                        else it.copy(isLoading = false, message = text)
                    }
                }
        }
    }
}

package com.maggie.app.ui.screens.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.api.TaskCreateRequest
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.mercure.MercureTopics
import com.maggie.app.data.mercure.coalescedAndRechecked
import com.maggie.app.data.model.DayRoutine
import com.maggie.app.data.model.Reward
import com.maggie.app.data.model.RoutineDayView
import com.maggie.app.data.model.RoutinesBand
import com.maggie.app.data.model.Task
import com.maggie.app.data.model.TaskView
import com.maggie.app.data.model.TaskViewCounts
import com.maggie.app.data.repository.RoutineRepository
import com.maggie.app.data.repository.TaskRepository
import com.maggie.app.data.repository.tasksOfDay
import com.maggie.app.data.reward.Celebration
import com.maggie.app.data.reward.TaskRewards
import com.maggie.app.util.DateRanges
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
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
    /** The last page of « Faites » read. */
    val page: Int = 1,
    /**
     * « Aujourd'hui » (MAG-766): the tasks of the day, to tick in any order — the open ones as the today view orders
     * them, then those done today. `null` until read.
     */
    val todayTasks: List<Task>? = null,
    /** The day [todayTasks] are of, which their pills are said from (« avant ce soir »). */
    val todayDate: LocalDate? = null,
    /** A task asked for from the day, read whole: the screen hands it to the detail and clears it. */
    val opened: Task? = null,
    /** A task of the day just ticked (MAG-508): the screen opens its Bravo and clears it. */
    val bravo: BravoRequest? = null,
    /** A task ticked in another tab: its reward as a short banner instead of the Bravo. */
    val reward: Celebration? = null,
    /** A palier reached (MAG-509), pushed by the server: gold banner over the screen, until dismissed. */
    val palier: Celebration? = null,
    /** The routines due today (MAG-512), shown as [routinesBand] above the ring; `null` until read. */
    val routines: RoutineDayView? = null,
    /** The band of « Aujourd'hui », absent when no routine is to tick. */
    val routinesBand: RoutinesBand? = null,
    /** Routines whose tick is in flight: a second tap waits for the first. */
    val routinesPending: Set<String> = emptySet(),
    /** « Routines » of the home widget asked for: the screen opens that tab and clears it. */
    val routinesTabRequested: Boolean = false,
) {
    /** What the open tab draws has arrived: the tasks of the day in « Aujourd'hui », the list elsewhere. */
    val hasContent: Boolean get() = if (view == TaskView.TODAY) todayTasks != null else tasks.isNotEmpty()

    /** « Aujourd'hui » says so itself when it holds nothing: its routines may be there. */
    val isEmpty: Boolean get() = !isLoading && error == null && view != TaskView.TODAY && tasks.isEmpty()

    /** The ring, « n sur N »: the tasks of the day ticked, out of all of them. */
    val todayDone: Int get() = todayTasks.orEmpty().count { it.isDone }

    val todayTotal: Int get() = todayTasks.orEmpty().size

    /** Only « Faites » is paged: more is out there when the page held back some of the view. */
    val hasMore: Boolean get() = view == TaskView.DONE && tasks.size < totalItems
}

/**
 * The tasks screen (MAG-408): one of four views at a time, read from `/api/tasks/view/{view}`.
 * The API decides which view a task is in and in which order; this class only asks again after
 * anything that can move a task (a tick, an add, a push).
 *
 * « Aujourd'hui » is the tasks of the day instead (MAG-766, [tasksOfDay]): the today view and the tasks done today,
 * ticked in any order, with no reserve — the other tasks are in their views. It stays so at any hour: the evening's
 * review is Maggie's « Bilan de la journée », not this screen's (MAG-825).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TasksViewModel(
    private val taskRepository: TaskRepository,
    private val mercureService: MercureService,
    private val authRepository: AuthRepository,
    private val rewards: TaskRewards,
    private val routineRepository: RoutineRepository,
    private val today: () -> LocalDate = { DateRanges.todayDate() },
    private val now: () -> LocalTime = { LocalTime.now() },
) : ViewModel() {

    private val _uiState = MutableStateFlow(TasksUiState())
    val uiState: StateFlow<TasksUiState> = _uiState

    private var loadJob: Job? = null
    private var routinesJob: Job? = null
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
                    coroutineScope {
                        // A palier arrives on its own: it moves no task, so it does not reload the list.
                        launch {
                            mercureService.subscribe(MercureTopics.userScoped(userId, MercureTopics.REWARDS))
                                .catch { /* SSE reconnects automatically */ }
                                .collect { event -> Reward.fromPush(event.data)?.takeIf { it.isPalier }?.let { onPalier(it) } }
                        }
                        // A routine ticked elsewhere moves no task: only its band is read again.
                        launch {
                            merge(
                                mercureService.subscribe(MercureTopics.userScoped(userId, MercureTopics.ROUTINES))
                                    .catch { /* SSE reconnects automatically */ },
                                mercureService.subscribe(MercureTopics.userScoped(userId, MercureTopics.ROUTINE_CHECKS))
                                    .catch { /* SSE reconnects automatically */ },
                            )
                                .coalescedAndRechecked()
                                .collect { reloadRoutines() }
                        }
                        mercureService.subscribe(MercureTopics.userScoped(userId, MercureTopics.TASKS))
                            .catch { /* SSE reconnects automatically */ }
                            .coalescedAndRechecked()
                            .collect { reload() }
                    }
                }
        }
    }

    /** Pull-to-refresh. */
    fun refresh() = reload()

    /** The home widget's title: the screen opens on its « Routines » tab. */
    fun requestRoutinesTab() {
        _uiState.update { it.copy(routinesTabRequested = true) }
    }

    fun onRoutinesTabShown() {
        _uiState.update { it.copy(routinesTabRequested = false) }
    }

    /**
     * Ticks a routine of the band, or unticks it. Shown at once, kept when the API took it, taken back
     * when it refused. A routine never counts in the day, earns no star and opens no Bravo.
     */
    fun toggleRoutine(routine: DayRoutine) {
        val state = _uiState.value
        val day = state.routines ?: return
        if (routine.id in state.routinesPending) return
        val checked = !(day.routines.firstOrNull { it.id == routine.id }?.checked ?: routine.checked)
        _uiState.update { it.copy(routinesPending = it.routinesPending + routine.id, message = null) }
        updateRoutines { it.withChecked(routine.id, checked) }
        viewModelScope.launch {
            routineRepository.setChecked(routine.id, LocalDate.parse(day.date), checked)
                .onSuccess { _uiState.update { it.copy(routinesPending = it.routinesPending - routine.id) } }
                .onFailure { e ->
                    updateRoutines { it.withChecked(routine.id, !checked) }
                    _uiState.update {
                        it.copy(
                            routinesPending = it.routinesPending - routine.id,
                            message = e.message ?: "La coche n'a pas été enregistrée.",
                        )
                    }
                }
        }
    }

    private fun updateRoutines(change: (RoutineDayView) -> RoutineDayView) {
        _uiState.update { s ->
            val day = s.routines?.let(change)
            s.copy(routines = day, routinesBand = RoutinesBand.of(day, now()))
        }
    }

    private fun reloadRoutines() {
        if (_uiState.value.view != TaskView.TODAY) return
        routinesJob?.cancel()
        routinesJob = viewModelScope.launch { loadRoutines() }
    }

    // A band that cannot be read leaves the one shown: the day does not depend on it.
    private suspend fun loadRoutines() {
        routineRepository.getDay(today()).onSuccess { day ->
            _uiState.update { it.copy(routines = day, routinesBand = RoutinesBand.of(day, now())) }
        }
    }

    fun selectView(view: TaskView) {
        if (view == _uiState.value.view) return
        // The other tab's tasks must not stay under this one's label while the read is in flight.
        _uiState.update { it.copy(view = view, tasks = emptyList(), totalItems = 0, message = null, page = 1) }
        load(spinner = true)
    }

    /**
     * Ticks a task, or unticks it; the view is read again since the task changes view. A task ticked
     * here, out of the day, shows its reward as a banner (MAG-508).
     */
    fun toggleDone(task: Task) {
        val done = !task.isDone
        viewModelScope.launch {
            taskRepository.toggleDone(task.id, done)
                .onSuccess {
                    reload()
                    if (done) rewards.celebrate(task.id)?.let { reward -> _uiState.update { it.copy(reward = reward) } }
                }
                .onFailure { _uiState.update { it.copy(message = "Impossible de modifier la tâche.") } }
        }
    }

    /** A palier pushed by the server: shown once, with the buzz and the sound the settings allow. */
    private suspend fun onPalier(reward: Reward) {
        if (_uiState.value.palier?.reward?.id == reward.id) return
        val celebration = rewards.celebrate(reward)
        _uiState.update { it.copy(palier = celebration) }
    }

    /** The palier banner was dismissed. */
    fun dismissPalier() {
        _uiState.update { it.copy(palier = null) }
    }

    /** The banner went away. */
    fun dismissReward() {
        _uiState.update { it.copy(reward = null) }
    }

    /** The screen opened the Bravo [toggleTodayTask] asked for. */
    fun onBravoShown() {
        _uiState.update { it.copy(bravo = null) }
    }

    /**
     * Ticks a task of the day, or unticks it, in any order (MAG-766): struck at once, the ring follows, and the day
     * is read again once the server took it; a refusal puts it back. A tick the server took opens the Bravo (MAG-508).
     */
    fun toggleTodayTask(task: Task) {
        val before = _uiState.value.todayTasks ?: return
        val done = !task.isDone
        val completedAt = if (done) Instant.now().toString() else null
        _uiState.update { s ->
            s.copy(todayTasks = s.todayTasks?.map { if (it.id == task.id) it.copy(completedAt = completedAt) else it }, message = null)
        }
        viewModelScope.launch {
            taskRepository.toggleDone(task.id, done)
                .onSuccess {
                    if (done) _uiState.update { it.copy(bravo = BravoRequest(task.id, task.title)) }
                    reload()
                }
                .onFailure { _uiState.update { it.copy(todayTasks = before, message = "Impossible de modifier la tâche.") } }
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

    /** The tasks of the day ([tasksOfDay]), whose today view also gives the counts the other tabs show. */
    private suspend fun loadDay(): Unit = coroutineScope {
        val date = today()
        val routines = async { loadRoutines() }
        taskRepository.tasksOfDay(date)
            .onSuccess { read ->
                _uiState.update { it.copy(todayTasks = read.tasks, todayDate = date, counts = read.counts, isLoading = false) }
            }
            .onFailure { e -> failed(e, "Impossible de charger les tâches du jour.") }
        routines.await()
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

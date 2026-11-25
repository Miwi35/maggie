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
import kotlinx.coroutines.delay
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class TasksUiState(
    val section: TasksSection = TasksSection.TODAY,
    /** « Archives » (the done tasks) in the API's order, each subtask after its parent. */
    val tasks: List<Task> = emptyList(),
    /** « Tâches » (MAG-934): « À venir » and « Un jour » grouped by week; `null` until read. */
    val stock: List<StockGroup>? = null,
    /** What each tab holds, refreshed by every read; `null` until the first one answered. */
    val counts: TaskViewCounts? = null,
    /** Size of the whole « Archives »: it is paged, so [tasks] can hold less. */
    val totalItems: Int = 0,
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    /** The read failed. */
    val error: String? = null,
    /** An action (tick, add) failed; the list is left as it was. */
    val message: String? = null,
    /** The last page of « Archives » read. */
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
    /** A repeated task ticked in « Tâches » (MAG-1033): its row stays struck under « Fait · prochaine le … » until it closes. */
    val recurringDone: RecurringDone? = null,
) {
    /** What the open tab draws has arrived: the tasks of the day in « Aujourd'hui », the list elsewhere. */
    val hasContent: Boolean
        get() = when (section) {
            TasksSection.TODAY -> todayTasks != null
            TasksSection.STOCK -> !stock.isNullOrEmpty()
            TasksSection.ARCHIVES -> tasks.isNotEmpty()
        }

    /** « Aujourd'hui » says so itself when it holds nothing: its routines may be there. */
    val isEmpty: Boolean
        get() = !isLoading && error == null && when (section) {
            TasksSection.TODAY -> false
            TasksSection.STOCK -> stock?.isEmpty() == true
            TasksSection.ARCHIVES -> tasks.isEmpty()
        }

    /** The ring, « n sur N »: the tasks of the day ticked, out of all of them. */
    val todayDone: Int get() = todayTasks.orEmpty().count { it.isDone }

    val todayTotal: Int get() = todayTasks.orEmpty().size

    /** Only « Archives » is paged: more is out there when the page held back some of the view. */
    val hasMore: Boolean get() = section == TasksSection.ARCHIVES && tasks.size < totalItems
}

/** The occurrence [taskId] of a series just ticked, and the day of the next one, `null` when the rule ended. */
data class RecurringDone(val taskId: String, val next: LocalDate?)

/** How long « Fait · prochaine le … » stays before the list shows the series at its next date (MAG-1033). */
const val RECURRING_DONE_MS = 6_000L

/**
 * The tasks screen (MAG-408): one of three sections at a time (« Tâches » reads two views), read from `/api/tasks/view/{view}`.
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
    private var recurringDoneJob: Job? = null

    /** The repeated task whose tick is in flight: its struck row must not be read away before the banner. */
    private var recurringTicking: String? = null

    /** A read asked for while the struck row is held, done once it is let go. */
    private var reloadHeld = false

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
        if (_uiState.value.section != TasksSection.TODAY) return
        routinesJob?.cancel()
        routinesJob = viewModelScope.launch { loadRoutines() }
    }

    // A band that cannot be read leaves the one shown: the day does not depend on it.
    private suspend fun loadRoutines() {
        routineRepository.getDay(today()).onSuccess { day ->
            _uiState.update { it.copy(routines = day, routinesBand = RoutinesBand.of(day, now())) }
        }
    }

    fun selectSection(section: TasksSection) {
        if (section == _uiState.value.section) return
        dropRecurringDone()
        // The other tab's tasks must not stay under this one's label while the read is in flight.
        _uiState.update { it.copy(section = section, tasks = emptyList(), stock = null, totalItems = 0, message = null, page = 1) }
        load(spinner = true)
    }

    /**
     * Ticks a task, or unticks it; the view is read again since the task changes view. A task ticked
     * here, out of the day, shows its reward as a banner (MAG-508). A repeated task ticked in « Tâches » is
     * [tickRecurring]; its struck row ticked again is [undoRecurringDone].
     */
    fun toggleDone(task: Task) {
        val shown = _uiState.value.recurringDone
        if (task.id == shown?.taskId) return undoRecurringDone()
        if (task.id == recurringTicking) return
        // Another row ticked lets the struck one go.
        if (shown != null) closeRecurringDone()
        val done = !task.isDone
        if (done && task.rrule != null && _uiState.value.section == TasksSection.STOCK) return tickRecurring(task)
        viewModelScope.launch {
            taskRepository.toggleDone(task.id, done)
                .onSuccess {
                    reload()
                    if (done) rewards.celebrate(task.id)?.let { reward -> _uiState.update { it.copy(reward = reward) } }
                }
                .onFailure { _uiState.update { it.copy(message = "Impossible de modifier la tâche.") } }
        }
    }

    /**
     * A repeated task ticked in « Tâches » (MAG-1033): struck at once, then « Fait · prochaine le … » for
     * [RECURRING_DONE_MS], the row held in place, even through a push; the list is read again once the banner closes.
     * A refusal puts the row back.
     */
    private fun tickRecurring(task: Task) {
        val before = _uiState.value.stock ?: return
        recurringTicking = task.id
        // A read already in flight would unstrike the row: it is done once the banner closes instead.
        if (loadJob?.isActive == true) {
            loadJob?.cancel()
            reloadHeld = true
        }
        strike(task.id, Instant.now().toString())
        viewModelScope.launch {
            taskRepository.toggleDone(task.id, true)
                .onSuccess { done ->
                    // The occurrence ticked is done; its series goes on in the next one, whose day the banner says.
                    val next = done.nextOccurrenceId?.let { id -> taskRepository.fetchTask(id).getOrNull()?.let { it.scheduledDay ?: it.dueDay } }
                    rewards.celebrate(task.id)?.let { reward -> _uiState.update { it.copy(reward = reward) } }
                    // The tab changed meanwhile: it was read without the row.
                    if (recurringTicking != task.id) return@onSuccess
                    recurringTicking = null
                    _uiState.update { it.copy(recurringDone = RecurringDone(task.id, next)) }
                    recurringDoneJob = viewModelScope.launch {
                        delay(RECURRING_DONE_MS)
                        recurringDoneJob = null
                        closeRecurringDone()
                    }
                }
                .onFailure {
                    if (recurringTicking != task.id) return@onFailure
                    recurringTicking = null
                    _uiState.update { it.copy(stock = before, message = "Impossible de modifier la tâche.") }
                    if (reloadHeld) {
                        reloadHeld = false
                        reload()
                    }
                }
        }
    }

    /** The banner of a repeated task closed: the list shows the series at its next date. */
    fun closeRecurringDone() {
        if (_uiState.value.recurringDone == null) return
        dropRecurringDone()
        reload()
    }

    /**
     * « Annuler », or the struck row ticked again: the occurrence is reopened — the API then gives the series back to
     * it — and the list read again.
     */
    fun undoRecurringDone() {
        val done = _uiState.value.recurringDone ?: return
        dropRecurringDone()
        recurringTicking = done.taskId
        strike(done.taskId, null)
        viewModelScope.launch {
            val undone = taskRepository.toggleDone(done.taskId, false)
            recurringTicking = null
            reloadHeld = false
            undone.onFailure { _uiState.update { it.copy(message = "Impossible d'annuler.") } }
            reload()
        }
    }

    /** The banner goes without a read: the caller reads what it needs. */
    private fun dropRecurringDone() {
        recurringDoneJob?.cancel()
        recurringDoneJob = null
        recurringTicking = null
        reloadHeld = false
        _uiState.update { it.copy(recurringDone = null) }
    }

    private fun strike(taskId: String, completedAt: String?) {
        _uiState.update { s ->
            s.copy(
                stock = s.stock?.map { g -> g.copy(tasks = g.tasks.map { if (it.id == taskId) it.copy(completedAt = completedAt) else it }) },
                message = null,
            )
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

    /** The next page of « Archives », when the user scrolled to the end of what is loaded. */
    fun loadMore() {
        val state = _uiState.value
        if (!state.hasMore || state.isLoading || state.isLoadingMore) return
        val page = state.page + 1
        _uiState.update { it.copy(isLoadingMore = true) }
        moreJob = viewModelScope.launch {
            taskRepository.getTaskView(TaskView.DONE, page)
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

    /** Every read but a change of tab: held while a repeated task's struck row is on screen (MAG-1033), pushes included. */
    private fun reload() {
        if (_uiState.value.recurringDone != null || recurringTicking != null) {
            reloadHeld = true
            return
        }
        load(spinner = !_uiState.value.hasContent)
    }

    private fun load(spinner: Boolean) {
        loadJob?.cancel()
        moreJob?.cancel()
        val section = _uiState.value.section
        _uiState.update { it.copy(isLoading = spinner || it.isLoading, isLoadingMore = false, error = null) }
        loadJob = viewModelScope.launch {
            when (section) {
                TasksSection.TODAY -> loadDay()
                TasksSection.STOCK -> loadStock()
                TasksSection.ARCHIVES -> loadView(TaskView.DONE)
            }
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

    /** « Tâches »: « À venir » and « Un jour », read together and grouped here. */
    private suspend fun loadStock(): Unit = coroutineScope {
        val date = today()
        val upcoming = async { taskRepository.getTaskView(TaskView.UPCOMING) }
        val someday = async { taskRepository.getTaskView(TaskView.SOMEDAY) }
        val up = upcoming.await()
        val some = someday.await()
        val failure = up.exceptionOrNull() ?: some.exceptionOrNull()
        if (failure != null) {
            failed(failure, "Impossible de charger les tâches.")
        } else {
            val groups = TasksStock.group(up.getOrThrow().member, some.getOrThrow().member, date)
            _uiState.update { it.copy(stock = groups, todayDate = date, counts = some.getOrThrow().counts, isLoading = false) }
        }
    }

    /**
     * « Aujourd'hui » on a row of « Tâches »: the task is planned today, moment and hour kept. The row leaves at once
     * with its subtasks, and the section is read again once the server took it; a refusal puts them back.
     */
    fun planToday(task: Task) {
        val before = _uiState.value.stock ?: return
        _uiState.update { s ->
            val left = s.stock.orEmpty()
                .map { g -> g.copy(tasks = g.tasks.filter { it.id != task.id && it.parent != task.iri }) }
                .filter { it.tasks.isNotEmpty() }
            s.copy(stock = left, message = null)
        }
        viewModelScope.launch {
            taskRepository.updateTask(task.id, buildJsonObject { put("scheduledDate", today().toString()) })
                .onSuccess { reload() }
                .onFailure { _uiState.update { it.copy(stock = before, message = "Impossible de programmer la tâche.") } }
        }
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

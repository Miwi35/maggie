package com.maggie.app.ui.screens.tasks

import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureEvent
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.mercure.MercureTopics
import com.maggie.app.data.model.Task
import com.maggie.app.data.model.TaskView
import com.maggie.app.data.model.TaskViewCounts
import com.maggie.app.data.model.TaskViewResponse
import com.maggie.app.data.model.DayRoutine
import com.maggie.app.data.model.Reward
import com.maggie.app.data.model.RoutineDayView
import com.maggie.app.data.model.RoutineMoment
import com.maggie.app.data.repository.RoutineRepository
import com.maggie.app.data.repository.TaskRepository
import com.maggie.app.data.reward.Celebration
import com.maggie.app.data.reward.RewardFeedback
import com.maggie.app.data.reward.TaskRewards
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

@OptIn(ExperimentalCoroutinesApi::class)
class TasksViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var taskRepository: TaskRepository
    private lateinit var routineRepository: RoutineRepository
    private lateinit var mercureService: MercureService
    private lateinit var authRepository: AuthRepository
    private lateinit var feedback: RewardFeedback
    private val pushes = MutableSharedFlow<MercureEvent>()
    private val rewardPushes = MutableSharedFlow<MercureEvent>()
    private val routinePushes = MutableSharedFlow<MercureEvent>()
    private val routineCheckPushes = MutableSharedFlow<MercureEvent>()

    private val counts = TaskViewCounts(today = 2, upcoming = 1, someday = 1, done = 1)
    private val late = task("t1", "Renvoyer le formulaire", dueDate = "2026-10-13", overdueDays = 2)
    private val canteen = task("t2", "Payer la cantine", dueDate = "2026-10-15")
    private val today = LocalDate.of(2026, 10, 15)

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        taskRepository = mockk()
        routineRepository = mockk()
        // No routine unless a test says so.
        coEvery { routineRepository.getDay(any()) } returns Result.success(RoutineDayView(date = "2026-10-15"))
        mercureService = mockk()
        authRepository = mockk(relaxed = true)
        feedback = mockk(relaxed = true)
        // No reward unless a test says so.
        coEvery { taskRepository.getTaskReward(any()) } returns Result.success(null)
        coEvery { taskRepository.getTaskSettings() } returns Result.failure(IllegalStateException("offline"))
        every { authRepository.token } returns flowOf("test-jwt")
        coEvery { authRepository.getUserId() } returns "u1"
        every { mercureService.subscribe(MercureTopics.userScoped("u1", MercureTopics.TASKS)) } returns pushes
        every { mercureService.subscribe(MercureTopics.userScoped("u1", MercureTopics.REWARDS)) } returns rewardPushes
        every { mercureService.subscribe(MercureTopics.userScoped("u1", MercureTopics.ROUTINES)) } returns routinePushes
        every { mercureService.subscribe(MercureTopics.userScoped("u1", MercureTopics.ROUTINE_CHECKS)) } returns routineCheckPushes
        // No constellation unless a test says so.
        coEvery { taskRepository.getStars(any()) } returns Result.failure(IllegalStateException("offline"))
        serve(TaskView.TODAY, late, canteen)
        serveDoneToday()
    }

    /** The tasks done today, between Paris midnights (MAG-766). */
    private fun serveDoneToday(vararg tasks: Task) {
        coEvery {
            taskRepository.getCompletedBetween("2026-10-15T00:00:00+02:00", "2026-10-16T00:00:00+02:00", any())
        } returns tasks.toList()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** The clock's day, which a test may move past midnight. */
    private var clockDay = LocalDate.of(2026, 10, 15)

    private fun createViewModel(now: LocalTime = LocalTime.of(10, 0)) =
        TasksViewModel(taskRepository, mercureService, authRepository, TaskRewards(taskRepository, feedback), routineRepository, today = { clockDay }, now = { now })

    /** « Aujourd'hui » opened and read. */
    private fun TestScope.openDay(): TasksViewModel {
        val viewModel = createViewModel()
        viewModel.onOpened()
        advanceUntilIdle()
        return viewModel
    }

    private fun task(
        id: String,
        title: String,
        dueDate: String? = null,
        completedAt: String? = null,
        parent: String? = null,
        overdueDays: Int? = null,
    ) = Task(id = id, title = title, dueDate = dueDate, completedAt = completedAt, parent = parent, overdueDays = overdueDays)

    private fun response(vararg tasks: Task, total: Int = tasks.size, counts: TaskViewCounts = this.counts) =
        TaskViewResponse(member = tasks.toList(), totalItems = total, counts = counts)

    private fun serve(view: TaskView, vararg tasks: Task, total: Int = tasks.size) {
        coEvery { taskRepository.getTaskView(view, null) } returns Result.success(response(*tasks, total = total))
    }

    @Test
    fun `starts in Aujourd'hui, loading`() {
        val state = createViewModel().uiState.value

        assertEquals(TaskView.TODAY, state.view)
        assertTrue(state.isLoading)
        assertNull(state.counts)
    }

    @Test
    fun `opening the screen lands the tasks of the day, open then done today, and the counts of every tab`() = runTest {
        val parcel = task("t9", "Renvoyer le colis", completedAt = "2026-10-15T07:12:00Z")
        serveDoneToday(parcel)
        val viewModel = createViewModel()
        viewModel.onOpened()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf("t1", "t2", "t9"), state.todayTasks?.map { it.id })
        assertEquals(today, state.todayDate)
        assertEquals(1, state.todayDone)
        assertEquals(3, state.todayTotal)
        assertEquals(counts, state.counts)
        assertFalse(state.isLoading)
        assertNull(state.error)
        assertFalse(state.isEmpty)
    }

    @Test
    fun `a subtask under a task of the day is no row of its own, one whose task is not listed is`() = runTest {
        val step = task("s1", "Chercher le numéro", dueDate = "2026-10-15", parent = Task.iriOf("t1"))
        val orphan = task("s2", "Dire la plaque", dueDate = "2026-10-15", parent = Task.iriOf("t7"))
        serve(TaskView.TODAY, late, step, canteen, orphan)
        val viewModel = createViewModel()
        viewModel.onOpened()
        advanceUntilIdle()

        assertEquals(listOf("t1", "t2", "s2"), viewModel.uiState.value.todayTasks?.map { it.id })
    }

    @Test
    fun `an unreadable list of the tasks done keeps the open ones`() = runTest {
        coEvery { taskRepository.getCompletedBetween(any(), any(), any()) } throws RuntimeException("offline")
        val viewModel = createViewModel()
        viewModel.onOpened()
        advanceUntilIdle()

        assertEquals(listOf("t1", "t2"), viewModel.uiState.value.todayTasks?.map { it.id })
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `the Aujourd'hui tab counts what is left of the tasks of the day, the others their view`() = runTest {
        serveDoneToday(task("t9", "Renvoyer le colis", completedAt = "2026-10-15T07:12:00Z"))
        val viewModel = createViewModel()
        viewModel.onOpened()
        advanceUntilIdle()

        assertEquals("Aujourd'hui (2)", tabLabelOf(TaskView.TODAY, viewModel.uiState.value))
        assertEquals("À venir (1)", tabLabelOf(TaskView.UPCOMING, viewModel.uiState.value))
    }
    @Test
    fun `a view with no task is empty, not loading and not in error`() = runTest {
        serve(TaskView.SOMEDAY)

        val viewModel = createViewModel()
        viewModel.selectView(TaskView.SOMEDAY)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isEmpty)
        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test
    fun `a day with no task is not an empty tab, the day says so itself`() = runTest {
        serve(TaskView.TODAY)

        val viewModel = createViewModel()
        viewModel.onOpened()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isEmpty)
        assertEquals(emptyList<Task>(), viewModel.uiState.value.todayTasks)
    }
    @Test
    fun `a failing read exposes the error and stops loading`() = runTest {
        coEvery { taskRepository.getTaskView(TaskView.TODAY, null) } returns Result.failure(RuntimeException("Network error"))

        val viewModel = createViewModel()
        viewModel.onOpened()
        advanceUntilIdle()

        assertEquals("Network error", viewModel.uiState.value.error)
        assertFalse(viewModel.uiState.value.isLoading)
        assertFalse(viewModel.uiState.value.isEmpty)
    }
    @Test
    fun `a refresh that fails under the day keeps the day and says so in a message`() = runTest {
        val viewModel = createViewModel()
        viewModel.onOpened()
        advanceUntilIdle()

        coEvery { taskRepository.getTaskView(TaskView.TODAY, null) } returns Result.failure(RuntimeException("Offline"))
        viewModel.refresh()
        advanceUntilIdle()

        assertEquals(2, viewModel.uiState.value.todayTotal)
        assertNull(viewModel.uiState.value.error)
        assertEquals("Offline", viewModel.uiState.value.message)
    }
    @Test
    fun `a refresh keeps the day on screen while it reads`() = runTest {
        val viewModel = createViewModel()
        viewModel.onOpened()
        advanceUntilIdle()

        val answer = CompletableDeferred<Result<TaskViewResponse>>()
        coEvery { taskRepository.getTaskView(TaskView.TODAY, null) } coAnswers { answer.await() }
        viewModel.refresh()
        runCurrent()

        assertEquals(2, viewModel.uiState.value.todayTotal)
        assertFalse(viewModel.uiState.value.isLoading)
        answer.complete(Result.success(response(canteen)))
        advanceUntilIdle()
        assertEquals(listOf("t2"), viewModel.uiState.value.todayTasks?.map { it.id })
    }
    @Test
    fun `a refresh in another tab that fails under its list keeps the list`() = runTest {
        serve(TaskView.UPCOMING, late, canteen)
        val viewModel = createViewModel()
        viewModel.selectView(TaskView.UPCOMING)
        advanceUntilIdle()

        coEvery { taskRepository.getTaskView(TaskView.UPCOMING, null) } returns Result.failure(RuntimeException("Offline"))
        viewModel.refresh()
        advanceUntilIdle()

        assertEquals(2, viewModel.uiState.value.tasks.size)
        assertEquals("Offline", viewModel.uiState.value.message)
    }

    @Test
    fun `changing tab empties the list, reads the new view and keeps the counts`() = runTest {
        val garage = task("t3", "Appeler le garage", dueDate = "2026-10-18")
        serve(TaskView.UPCOMING, garage)
        val viewModel = createViewModel()
        viewModel.onOpened()
        advanceUntilIdle()

        viewModel.selectView(TaskView.UPCOMING)
        assertEquals(TaskView.UPCOMING, viewModel.uiState.value.view)
        assertTrue(viewModel.uiState.value.tasks.isEmpty())
        assertTrue(viewModel.uiState.value.isLoading)

        advanceUntilIdle()
        assertEquals(listOf("t3"), viewModel.uiState.value.tasks.map { it.id })
        assertFalse(viewModel.uiState.value.isLoading)
        assertEquals(counts, viewModel.uiState.value.counts)
    }

    @Test
    fun `an answer for the tab the user left does not overwrite the one now open`() = runTest {
        val slowToday = CompletableDeferred<Result<TaskViewResponse>>()
        coEvery { taskRepository.getTaskView(TaskView.TODAY, null) } coAnswers { slowToday.await() }
        serve(TaskView.SOMEDAY, task("t4", "Trier les photos"))
        val viewModel = createViewModel()
        viewModel.onOpened()
        runCurrent()

        viewModel.selectView(TaskView.SOMEDAY)
        advanceUntilIdle()
        slowToday.complete(Result.success(response(late)))
        advanceUntilIdle()

        assertEquals(listOf("t4"), viewModel.uiState.value.tasks.map { it.id })
        assertNull(viewModel.uiState.value.todayTasks)
    }
    @Test
    fun `selecting the tab already open reads nothing again`() = runTest {
        val viewModel = createViewModel()
        viewModel.onOpened()
        advanceUntilIdle()

        viewModel.selectView(TaskView.TODAY)
        advanceUntilIdle()

        coVerify(exactly = 1) { taskRepository.getTaskView(TaskView.TODAY, null) }
    }

    @Test
    fun `ticking a task reads the active view again`() = runTest {
        coEvery { taskRepository.toggleDone("t1", true) } returns Result.success(late.copy(completedAt = "2026-10-15T08:00:00Z"))
        serve(TaskView.UPCOMING, late, canteen)
        val viewModel = createViewModel()
        viewModel.selectView(TaskView.UPCOMING)
        advanceUntilIdle()

        serve(TaskView.UPCOMING, canteen)
        viewModel.toggleDone(late)
        advanceUntilIdle()

        coVerify { taskRepository.toggleDone("t1", true) }
        assertEquals(listOf("t2"), viewModel.uiState.value.tasks.map { it.id })
    }

    private val reward = Reward(
        id = "rw1",
        subjectType = "task",
        subjectId = "t1",
        level = "standard",
        messageKey = "doux.2",
        message = "Une de moins. Tu avances.",
        star = true,
        earnedAt = "2026-10-15T08:00:00+00:00",
    )

    @Test
    fun `a task ticked out of the day shows its reward as a banner, which goes away (MAG-508)`() = runTest {
        coEvery { taskRepository.toggleDone("t1", true) } returns Result.success(late.copy(completedAt = "2026-10-15T08:00:00Z"))
        coEvery { taskRepository.getTaskReward("t1") } returns Result.success(reward)
        serve(TaskView.UPCOMING, late, canteen)
        val viewModel = createViewModel()
        viewModel.selectView(TaskView.UPCOMING)
        advanceUntilIdle()

        viewModel.toggleDone(late)
        advanceUntilIdle()

        assertEquals(Celebration(reward, animate = true), viewModel.uiState.value.reward)
        // Out of the day: a banner, never the Bravo.
        assertNull(viewModel.uiState.value.bravo)
        viewModel.dismissReward()
        assertNull(viewModel.uiState.value.reward)
    }

    @Test
    fun `unticking a task out of the day reads no reward (MAG-508)`() = runTest {
        val done = task("t5", "Sortir les poubelles", completedAt = "2026-10-14T20:15:00Z")
        serve(TaskView.DONE, done)
        coEvery { taskRepository.toggleDone("t5", false) } returns Result.success(done.copy(completedAt = null))
        val viewModel = createViewModel()
        viewModel.selectView(TaskView.DONE)
        advanceUntilIdle()

        viewModel.toggleDone(done)
        advanceUntilIdle()

        coVerify(exactly = 0) { taskRepository.getTaskReward(any()) }
        assertNull(viewModel.uiState.value.reward)
    }

    @Test
    fun `unticking a done task asks for completedAt null`() = runTest {
        val done = task("t5", "Sortir les poubelles", completedAt = "2026-10-14T20:15:00Z")
        serve(TaskView.DONE, done)
        coEvery { taskRepository.toggleDone("t5", false) } returns Result.success(done.copy(completedAt = null))
        val viewModel = createViewModel()
        viewModel.selectView(TaskView.DONE)
        advanceUntilIdle()

        serve(TaskView.DONE)
        viewModel.toggleDone(done)
        advanceUntilIdle()

        coVerify { taskRepository.toggleDone("t5", false) }
        assertTrue(viewModel.uiState.value.isEmpty)
    }

    @Test
    fun `a tick that fails leaves the list and says so`() = runTest {
        coEvery { taskRepository.toggleDone("t1", true) } returns Result.failure(RuntimeException("boom"))
        serve(TaskView.UPCOMING, late, canteen)
        val viewModel = createViewModel()
        viewModel.selectView(TaskView.UPCOMING)
        advanceUntilIdle()

        viewModel.toggleDone(late)
        advanceUntilIdle()

        assertEquals(2, viewModel.uiState.value.tasks.size)
        assertNotNull(viewModel.uiState.value.message)
        viewModel.dismissMessage()
        assertNull(viewModel.uiState.value.message)
    }

    private fun doneTask(n: Int) = task("d$n", "Faite $n", completedAt = "2026-10-14T20:15:00Z")

    @Test
    fun `Faites loads its next page when asked and appends it`() = runTest {
        val firstPage = (1..30).map(::doneTask).toTypedArray()
        val secondPage = (31..35).map(::doneTask).toTypedArray()
        serve(TaskView.DONE, *firstPage, total = 35)
        coEvery { taskRepository.getTaskView(TaskView.DONE, 2) } returns Result.success(response(*secondPage, total = 35))
        val viewModel = createViewModel()
        viewModel.selectView(TaskView.DONE)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.hasMore)

        viewModel.loadMore()
        assertTrue(viewModel.uiState.value.isLoadingMore)
        advanceUntilIdle()

        assertEquals(35, viewModel.uiState.value.tasks.size)
        assertEquals("d35", viewModel.uiState.value.tasks.last().id)
        assertFalse(viewModel.uiState.value.hasMore)
        assertFalse(viewModel.uiState.value.isLoadingMore)

        viewModel.loadMore()
        advanceUntilIdle()
        coVerify(exactly = 1) { taskRepository.getTaskView(TaskView.DONE, 2) }
    }

    @Test
    fun `a task that moved between two pages is not listed twice`() = runTest {
        serve(TaskView.DONE, doneTask(1), doneTask(2), total = 3)
        coEvery { taskRepository.getTaskView(TaskView.DONE, 2) } returns Result.success(response(doneTask(2), doneTask(3), total = 3))
        val viewModel = createViewModel()
        viewModel.selectView(TaskView.DONE)
        advanceUntilIdle()

        viewModel.loadMore()
        advanceUntilIdle()

        assertEquals(listOf("d1", "d2", "d3"), viewModel.uiState.value.tasks.map { it.id })
    }

    @Test
    fun `the views that are not paged never ask for a next page`() = runTest {
        val viewModel = createViewModel()
        viewModel.onOpened()
        advanceUntilIdle()

        viewModel.loadMore()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.hasMore)
        coVerify(exactly = 0) { taskRepository.getTaskView(any(), 2) }
    }

    @Test
    fun `a next page that fails keeps what is loaded and says so`() = runTest {
        serve(TaskView.DONE, doneTask(1), total = 40)
        coEvery { taskRepository.getTaskView(TaskView.DONE, 2) } returns Result.failure(RuntimeException("boom"))
        val viewModel = createViewModel()
        viewModel.selectView(TaskView.DONE)
        advanceUntilIdle()

        viewModel.loadMore()
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.tasks.size)
        assertFalse(viewModel.uiState.value.isLoadingMore)
        assertNotNull(viewModel.uiState.value.message)
    }

    @Test
    fun `a task changed elsewhere reads the active view again`() = runTest {
        serve(TaskView.UPCOMING, late, canteen)
        val viewModel = createViewModel()
        viewModel.selectView(TaskView.UPCOMING)
        viewModel.onOpened()
        advanceUntilIdle()

        serve(TaskView.UPCOMING, canteen)
        pushes.emit(MercureEvent())
        advanceUntilIdle()

        assertEquals(listOf("t2"), viewModel.uiState.value.tasks.map { it.id })
    }

    @Test
    fun `a task ticked elsewhere reads the tasks of the day again`() = runTest {
        val viewModel = openDay()

        serve(TaskView.TODAY, canteen)
        serveDoneToday(late.copy(completedAt = "2026-10-15T08:00:00Z"))
        pushes.emit(MercureEvent())
        advanceUntilIdle()

        assertEquals(listOf("t2", "t1"), viewModel.uiState.value.todayTasks?.map { it.id })
        assertEquals(1, viewModel.uiState.value.todayDone)
    }
    // --- The tasks of the day (MAG-766) ---

    @Test
    fun `a task of the day ticked is struck at once, the ring follows, then the day is read again`() = runTest {
        coEvery { taskRepository.toggleDone("t2", true) } returns Result.success(canteen.copy(completedAt = "2026-10-15T08:00:00Z"))
        val viewModel = openDay()

        serve(TaskView.TODAY, late)
        serveDoneToday(canteen.copy(completedAt = "2026-10-15T08:00:00Z"))
        // The second one first: the day is done in any order.
        viewModel.toggleTodayTask(canteen)
        assertTrue(viewModel.uiState.value.todayTasks!!.first { it.id == "t2" }.isDone)
        assertEquals(1, viewModel.uiState.value.todayDone)
        assertEquals("Aujourd'hui (1)", tabLabelOf(TaskView.TODAY, viewModel.uiState.value))
        advanceUntilIdle()

        coVerify { taskRepository.toggleDone("t2", true) }
        assertEquals(listOf("t1", "t2"), viewModel.uiState.value.todayTasks?.map { it.id })
        assertEquals(1, viewModel.uiState.value.todayDone)
        assertEquals(2, viewModel.uiState.value.todayTotal)
    }

    @Test
    fun `a task of the day unticked asks for completedAt null and the ring goes back`() = runTest {
        val doneCanteen = canteen.copy(completedAt = "2026-10-15T08:00:00Z")
        serve(TaskView.TODAY, late)
        serveDoneToday(doneCanteen)
        coEvery { taskRepository.toggleDone("t2", false) } returns Result.success(canteen)
        val viewModel = openDay()
        assertEquals(1, viewModel.uiState.value.todayDone)

        viewModel.toggleTodayTask(doneCanteen)
        assertEquals(0, viewModel.uiState.value.todayDone)
        advanceUntilIdle()

        coVerify { taskRepository.toggleDone("t2", false) }
    }

    @Test
    fun `a task of the day ticked opens its Bravo once the server took it, not before (MAG-508)`() = runTest {
        val answer = CompletableDeferred<Result<Task>>()
        coEvery { taskRepository.toggleDone("t1", true) } coAnswers { answer.await() }
        val viewModel = openDay()

        viewModel.toggleTodayTask(late)
        runCurrent()
        assertNull(viewModel.uiState.value.bravo)

        answer.complete(Result.success(late))
        advanceUntilIdle()
        assertEquals(BravoRequest("t1", "Renvoyer le formulaire"), viewModel.uiState.value.bravo)
        // The Bravo reads its own reward: the day shows no banner.
        assertNull(viewModel.uiState.value.reward)

        viewModel.onBravoShown()
        assertNull(viewModel.uiState.value.bravo)
    }
    @Test
    fun `after the evening time the day stays open, a task ticks as at any hour (MAG-825)`() = runTest {
        // The evening's review is the « Bilan de la journée » (MAG-825): the day reads no evening time, no review.
        coEvery { taskRepository.toggleDone("t1", true) } returns Result.success(late)
        val viewModel = createViewModel(now = LocalTime.of(21, 30))
        viewModel.onOpened()
        advanceUntilIdle()

        assertEquals(listOf("t1", "t2"), viewModel.uiState.value.todayTasks?.map { it.id })
        viewModel.toggleTodayTask(late)
        advanceUntilIdle()

        assertEquals(BravoRequest("t1", "Renvoyer le formulaire"), viewModel.uiState.value.bravo)
        coVerify { taskRepository.toggleDone("t1", true) }
        coVerify(exactly = 0) { taskRepository.getDayPlan(any()) }
    }

    @Test
    fun `a task of the day unticked, or a tick refused, opens no Bravo (MAG-508)`() = runTest {
        val doneLate = late.copy(completedAt = "2026-10-15T08:00:00Z")
        serve(TaskView.TODAY, canteen)
        serveDoneToday(doneLate)
        coEvery { taskRepository.toggleDone("t1", false) } returns Result.success(late)
        coEvery { taskRepository.toggleDone("t2", true) } returns Result.failure(RuntimeException("boom"))
        val viewModel = openDay()

        viewModel.toggleTodayTask(doneLate)
        advanceUntilIdle()
        viewModel.toggleTodayTask(canteen)
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.bravo)
    }
    @Test
    fun `a tick of the day that fails puts the task back and says so`() = runTest {
        coEvery { taskRepository.toggleDone("t1", true) } returns Result.failure(RuntimeException("boom"))
        val viewModel = openDay()

        viewModel.toggleTodayTask(late)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.todayTasks!!.first { it.id == "t1" }.isDone)
        assertEquals(0, viewModel.uiState.value.todayDone)
        assertEquals("Impossible de modifier la tâche.", viewModel.uiState.value.message)
    }
    @Test
    fun `a task of the day is read whole before its detail opens`() = runTest {
        val whole = task("d1", "Renvoyer le formulaire", dueDate = "2026-10-13")
        coEvery { taskRepository.fetchTask("d1") } returns Result.success(whole)
        val viewModel = openDay()

        viewModel.openTask("d1")
        advanceUntilIdle()
        assertEquals(whole, viewModel.uiState.value.opened)

        viewModel.onTaskOpened()
        assertNull(viewModel.uiState.value.opened)
    }

    @Test
    fun `a task of the day that cannot be read says so and opens nothing`() = runTest {
        coEvery { taskRepository.fetchTask("d1") } returns Result.failure(RuntimeException("gone"))
        val viewModel = openDay()

        viewModel.openTask("d1")
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.opened)
        assertNotNull(viewModel.uiState.value.message)
    }

    @Test
    fun `the push stream is opened with the screen and released with it`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()
        verify(exactly = 0) { mercureService.subscribe(any()) }

        viewModel.onOpened()
        advanceUntilIdle()
        assertEquals(1, pushes.subscriptionCount.value)

        viewModel.onOpened()
        advanceUntilIdle()
        assertEquals(1, pushes.subscriptionCount.value)

        viewModel.onClosed()
        advanceUntilIdle()
        assertEquals(0, pushes.subscriptionCount.value)
    }

    private val palierPush = """{"@id":"/api/rewards/rw9","subjectType":"saga_chapter","subjectId":"c1","level":"palier","surprise":null,""" +
        """"messageKey":"palier-doux.1","message":"Un chapitre de bouclé. Prends le temps d'en profiter.","title":"Chapitre 1","star":false,""" +
        """"earnedAt":"2026-10-15T20:00:00+00:00"}"""

    @Test
    fun `a palier pushed while the screen is on is shown, and goes away when dismissed (MAG-509)`() = runTest {
        val viewModel = createViewModel()
        viewModel.onOpened()
        advanceUntilIdle()

        rewardPushes.emit(MercureEvent(data = palierPush))
        advanceUntilIdle()

        val palier = viewModel.uiState.value.palier
        assertEquals("Chapitre 1", palier?.reward?.title)
        assertTrue(palier!!.reward.isPalier)
        assertFalse(palier.reward.star)
        // A palier moves no task: the list was not read again for it.
        coVerify(exactly = 1) { taskRepository.getTaskView(TaskView.TODAY, null) }

        viewModel.dismissPalier()
        assertNull(viewModel.uiState.value.palier)
    }

    @Test
    fun `a standard reward pushed, or something that is no reward, shows no palier (MAG-509)`() = runTest {
        val viewModel = createViewModel()
        viewModel.onOpened()
        advanceUntilIdle()

        rewardPushes.emit(MercureEvent(data = palierPush.replace("\"palier\"", "\"standard\"")))
        rewardPushes.emit(MercureEvent(data = """{"@id":"/api/tasks/t1","title":"x"}"""))
        rewardPushes.emit(MercureEvent(data = "not json"))
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.palier)
    }

    @Test
    fun `a palier plays the buzz and the sound its settings allow (MAG-509)`() = runTest {
        coEvery { taskRepository.getTaskSettings() } returns Result.success(
            mockk(relaxed = true) {
                every { rewardVibration } returns true
                every { rewardSound } returns false
                every { rewardAnimation } returns false
            },
        )
        val viewModel = createViewModel()
        viewModel.onOpened()
        advanceUntilIdle()

        rewardPushes.emit(MercureEvent(data = palierPush))
        advanceUntilIdle()

        verify(exactly = 1) { feedback.vibrate() }
        verify(exactly = 0) { feedback.playSound() }
        assertFalse(viewModel.uiState.value.palier!!.animate)
    }

    @Test
    fun `the rewards stream is opened with the screen and released with it (MAG-509)`() = runTest {
        val viewModel = createViewModel()

        viewModel.onOpened()
        advanceUntilIdle()
        assertEquals(1, rewardPushes.subscriptionCount.value)

        viewModel.onClosed()
        advanceUntilIdle()
        assertEquals(0, rewardPushes.subscriptionCount.value)
    }

    private fun routine(id: String, title: String, anchor: String = "wake", checked: Boolean = false) =
        DayRoutine(id = id, title = title, anchor = anchor, time = "07:30", checked = checked)

    private fun routinesDay(vararg moments: RoutineMoment) = RoutineDayView(date = "2026-10-15", moments = moments.toList())

    private val wakeMoment get() = RoutineMoment("wake", "07:30", listOf(routine("r-water", "Boire un verre d'eau"), routine("r-vit", "Vitamines")))

    private fun serveRoutines(day: RoutineDayView) {
        coEvery { routineRepository.getDay(today) } returns Result.success(day)
    }

    @Test
    fun `the routines of the moment form the band above the day (MAG-512)`() = runTest {
        serveRoutines(routinesDay(wakeMoment, RoutineMoment("time", "21:00", listOf(routine("r-night", "Lire", "time")))))
        val viewModel = openDay()

        val band = viewModel.uiState.value.routinesBand!!
        assertEquals("Routines · matin", band.title)
        assertEquals(listOf("r-water", "r-vit"), band.routines.map { it.id })
    }

    @Test
    fun `no routine due, no band (MAG-512)`() = runTest {
        val viewModel = openDay()

        assertNull(viewModel.uiState.value.routinesBand)
    }

    @Test
    fun `unreadable routines leave the day as it is (MAG-512)`() = runTest {
        coEvery { routineRepository.getDay(any()) } returns Result.failure(IllegalStateException("offline"))
        val viewModel = openDay()

        assertNull(viewModel.uiState.value.routinesBand)
        assertEquals(listOf("t1", "t2"), viewModel.uiState.value.todayTasks?.map { it.id })
    }

    @Test
    fun `ticking a pill ticks the routine at once and sends the tick (MAG-512)`() = runTest {
        serveRoutines(routinesDay(wakeMoment))
        coEvery { routineRepository.setChecked("r-water", today, true) } returns Result.success(mockk(relaxed = true))
        val viewModel = openDay()

        viewModel.toggleRoutine(viewModel.uiState.value.routinesBand!!.routines.first())
        runCurrent()
        assertTrue(viewModel.uiState.value.routinesBand!!.routines.first { it.id == "r-water" }.checked)
        advanceUntilIdle()

        coVerify(exactly = 1) { routineRepository.setChecked("r-water", today, true) }
        assertTrue(viewModel.uiState.value.routinesPending.isEmpty())
        assertEquals(1, viewModel.uiState.value.routines!!.doneCount)
    }

    @Test
    fun `a ticked pill ticked again is unticked (MAG-512)`() = runTest {
        serveRoutines(routinesDay(RoutineMoment("wake", "07:30", listOf(routine("r-water", "Boire un verre d'eau", checked = true)))))
        coEvery { routineRepository.setChecked("r-water", today, false) } returns Result.success(mockk(relaxed = true))
        val viewModel = openDay()

        viewModel.toggleRoutine(viewModel.uiState.value.routinesBand!!.routines.first())
        advanceUntilIdle()

        coVerify(exactly = 1) { routineRepository.setChecked("r-water", today, false) }
        assertFalse(viewModel.uiState.value.routinesBand!!.routines.first().checked)
    }

    @Test
    fun `a refused tick is taken back and told (MAG-512)`() = runTest {
        serveRoutines(routinesDay(wakeMoment))
        coEvery { routineRepository.setChecked("r-water", today, true) } returns Result.failure(IllegalStateException("boom"))
        val viewModel = openDay()

        viewModel.toggleRoutine(viewModel.uiState.value.routinesBand!!.routines.first())
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.routinesBand!!.routines.first { it.id == "r-water" }.checked)
        assertNotNull(viewModel.uiState.value.message)
        assertTrue(viewModel.uiState.value.routinesPending.isEmpty())
    }

    @Test
    fun `a routine moves neither the ring nor the star (MAG-512)`() = runTest {
        serveRoutines(routinesDay(wakeMoment))
        coEvery { routineRepository.setChecked(any(), any(), any()) } returns Result.success(mockk(relaxed = true))
        val viewModel = openDay()
        val before = viewModel.uiState.value.todayTasks

        viewModel.toggleRoutine(viewModel.uiState.value.routinesBand!!.routines.first())
        advanceUntilIdle()

        assertEquals(before, viewModel.uiState.value.todayTasks)
        assertNull(viewModel.uiState.value.palier)
        coVerify(exactly = 0) { taskRepository.getTaskReward(any()) }
    }

    @Test
    fun `a routine ticked elsewhere refreshes the band (MAG-512)`() = runTest {
        serveRoutines(routinesDay(wakeMoment))
        val viewModel = openDay()

        serveRoutines(routinesDay(RoutineMoment("wake", "07:30", wakeMoment.routines.map { it.copy(checked = true) })))
        routineCheckPushes.emit(MercureEvent())
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.routinesBand!!.routines.all { it.checked })
        assertEquals(1, routineCheckPushes.subscriptionCount.value)
    }

    @Test
    fun `a routine changed elsewhere refreshes the band (MAG-512)`() = runTest {
        val viewModel = openDay()
        assertNull(viewModel.uiState.value.routinesBand)

        serveRoutines(routinesDay(wakeMoment))
        routinePushes.emit(MercureEvent())
        advanceUntilIdle()

        assertEquals(2, viewModel.uiState.value.routinesBand!!.routines.size)
    }

    @Test
    fun `the home widget asks for the routines tab, once (MAG-512)`() = runTest {
        val viewModel = createViewModel()

        viewModel.requestRoutinesTab()
        assertTrue(viewModel.uiState.value.routinesTabRequested)
        viewModel.onRoutinesTabShown()
        assertFalse(viewModel.uiState.value.routinesTabRequested)
    }
}

package com.maggie.app.ui.screens.tasks

import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureEvent
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.mercure.MercureTopics
import com.maggie.app.data.model.DayPlan
import com.maggie.app.data.model.DayReview
import com.maggie.app.data.model.DaySlot
import com.maggie.app.data.model.DayTask
import com.maggie.app.data.model.Task
import com.maggie.app.data.model.TaskView
import com.maggie.app.data.model.TaskViewCounts
import com.maggie.app.data.model.TaskViewResponse
import com.maggie.app.data.model.DayRoutine
import com.maggie.app.data.model.Reward
import com.maggie.app.data.model.RoutineDayView
import com.maggie.app.data.model.RoutineMoment
import com.maggie.app.data.model.Stars
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
    private val dayPushes = MutableSharedFlow<MercureEvent>()
    private val rewardPushes = MutableSharedFlow<MercureEvent>()
    private val routinePushes = MutableSharedFlow<MercureEvent>()
    private val routineCheckPushes = MutableSharedFlow<MercureEvent>()

    private val counts = TaskViewCounts(today = 2, upcoming = 1, someday = 1, done = 1)
    private val late = task("t1", "Renvoyer le formulaire", dueDate = "2026-10-13", overdueDays = 2)
    private val canteen = task("t2", "Payer la cantine", dueDate = "2026-10-15")
    private val today = LocalDate.of(2026, 10, 15)

    private val form = dayTask("d1", "Renvoyer le formulaire", postponedSince = "2026-10-13")
    private val call = dayTask("d2", "Appeler le garage", minutes = 10)
    private val bins = dayTask("d3", "Sortir les poubelles")
    private val plants = dayTask("d4", "Arroser")
    private val bread = dayTask("d5", "Acheter du pain")
    private val photos = dayTask("r1", "Trier les photos")
    private val books = dayTask("r2", "Ranger les livres")

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
        every { mercureService.subscribe(MercureTopics.userScoped("u1", MercureTopics.DAY_PLANS)) } returns dayPushes
        every { mercureService.subscribe(MercureTopics.userScoped("u1", MercureTopics.REWARDS)) } returns rewardPushes
        every { mercureService.subscribe(MercureTopics.userScoped("u1", MercureTopics.ROUTINES)) } returns routinePushes
        every { mercureService.subscribe(MercureTopics.userScoped("u1", MercureTopics.ROUTINE_CHECKS)) } returns routineCheckPushes
        // No constellation unless a test says so.
        coEvery { taskRepository.getStars(any()) } returns Result.failure(IllegalStateException("offline"))
        // Unreadable evening time: the evening never opens by itself unless a test says so.
        coEvery { taskRepository.getEveningTime() } returns Result.failure(IllegalStateException("offline"))
        serve(TaskView.TODAY, late, canteen)
        serveDoneToday()
        serveDay(day(listOf(form, call), reserve = listOf(photos, books)))
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

    private fun dayTask(id: String, title: String, minutes: Int? = null, postponedSince: String? = null, completedAt: String? = null) =
        DayTask(id = id, title = title, estimatedMinutes = minutes, postponedSince = postponedSince, completedAt = completedAt)

    /** The day as the API describes it: positions from 1, `now` the first slot not done. */
    private fun day(slotted: List<DayTask>, reserve: List<DayTask> = emptyList()): DayPlan {
        val slots = slotted.mapIndexed { index, task -> DaySlot(position = index + 1, task = task) }
        return DayPlan(
            id = "day",
            date = "2026-10-15",
            slots = slots,
            now = slots.firstOrNull { !it.task.isDone },
            reserve = reserve,
            done = slots.count { it.task.isDone },
            total = slots.size,
        )
    }

    private fun serveDay(day: DayPlan) {
        coEvery { taskRepository.getDayPlan(today) } returns Result.success(day)
    }

    /** « Aujourd'hui » opened and read. */
    private fun TestScope.openDay(): TasksViewModel {
        val viewModel = createViewModel()
        viewModel.onOpened()
        advanceUntilIdle()
        return viewModel
    }

    private val known get() = listOf(form, call, bins, plants, bread, photos, books)

    /** What the API would answer for [ids]: those in the slots, every other known task in the reserve. */
    private fun dayOf(ids: List<String>): DayPlan =
        day(ids.map { id -> known.first { it.id == id } }, reserve = known.filter { it.id !in ids })

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
        assertNull(state.day)
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
    fun `an unreadable day keeps the tasks of the day`() = runTest {
        coEvery { taskRepository.getDayPlan(today) } returns Result.failure(RuntimeException("offline"))
        val viewModel = createViewModel()
        viewModel.onOpened()
        advanceUntilIdle()

        assertEquals(listOf("t1", "t2"), viewModel.uiState.value.todayTasks?.map { it.id })
        assertNull(viewModel.uiState.value.day)
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
        serveDay(day(emptyList(), reserve = listOf(photos)))

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
    fun `a day changed elsewhere is read again`() = runTest {
        val viewModel = openDay()

        serve(TaskView.TODAY, canteen)
        serveDay(day(listOf(call)))
        dayPushes.emit(MercureEvent())
        advanceUntilIdle()

        assertEquals(listOf("t2"), viewModel.uiState.value.todayTasks?.map { it.id })
        assertEquals(listOf("d2"), viewModel.uiState.value.day?.taskIds)
        assertEquals(1, dayPushes.subscriptionCount.value)
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

    // « Le soir » (MAG-500)

    private val tomorrow = today.plusDays(1)
    private val doneAt = "2026-10-15T09:00:00Z"

    /** « 3 sur 5 »: the form, the call and the bins done, the plants and the bread left. */
    private val evening = day(
        listOf(
            form.copy(completedAt = doneAt),
            plants,
            call.copy(completedAt = doneAt),
            bread.copy(dueDate = "2026-10-17"),
            bins.copy(completedAt = doneAt),
        ),
    )

    private val todayPuts = mutableListOf<List<String>>()
    private val tomorrowPuts = mutableListOf<List<String>>()

    /** The evening opened at 21:05 on [evening], tomorrow holding [tomorrowTasks]; the PUTs of both days captured. */
    private fun TestScope.openEvening(
        tomorrowTasks: List<DayTask> = emptyList(),
        reviewedAt: String? = null,
        now: LocalTime = LocalTime.of(21, 5),
    ): TasksViewModel {
        serveDay(evening)
        coEvery { taskRepository.getEveningTime() } returns Result.success("21:00")
        coEvery { taskRepository.getDayReview(today) } returns Result.success(DayReview(id = "rv", date = today.toString(), reviewedAt = reviewedAt))
        coEvery { taskRepository.getDayPlan(tomorrow) } returns Result.success(day(tomorrowTasks).copy(date = tomorrow.toString()))
        coEvery { taskRepository.setDaySlots(today, capture(todayPuts), true) } answers { Result.success(dayOf(todayPuts.last())) }
        coEvery { taskRepository.setDaySlots(tomorrow, capture(tomorrowPuts), false) } answers {
            Result.success(dayOf(tomorrowPuts.last()).copy(date = tomorrow.toString()))
        }
        coEvery { taskRepository.setDueDate(any(), any()) } answers { Result.success(task(firstArg(), "t")) }
        val viewModel = createViewModel(now = now)
        viewModel.onOpened()
        advanceUntilIdle()
        return viewModel
    }

    @Test
    fun `the evening opens by itself after the evening time, what is done first, then what is left`() = runTest {
        val viewModel = openEvening()

        val state = viewModel.uiState.value.evening
        assertNotNull(state)
        assertEquals(listOf("d1", "d2", "d3"), state!!.done.map { it.id })
        assertEquals(listOf("d4", "d5"), state.left.map { it.id })
        assertEquals(5, state.day.total)
        assertEquals(EveningOutcome.LATER, state.outcomeOf("d4"))
        assertEquals(emptyList<String>(), state.tomorrowIds)
    }

    @Test
    fun `a day with no slot written reviews the tasks of the day in the evening (MAG-766)`() = runTest {
        val doneLate = late.copy(completedAt = doneAt)
        serve(TaskView.TODAY, canteen)
        serveDoneToday(doneLate)
        serveDay(day(emptyList()))
        coEvery { taskRepository.getEveningTime() } returns Result.success("21:00")
        coEvery { taskRepository.getDayReview(today) } returns Result.success(DayReview(id = "rv", date = today.toString()))
        coEvery { taskRepository.getDayPlan(tomorrow) } returns Result.success(day(emptyList()).copy(date = tomorrow.toString()))
        coEvery { taskRepository.setDueDate("t2", null) } returns Result.success(canteen.copy(dueDate = null))
        val viewModel = createViewModel(now = LocalTime.of(21, 5))
        viewModel.onOpened()
        advanceUntilIdle()

        val evening = viewModel.uiState.value.evening
        assertNotNull(evening)
        assertEquals(listOf("t1"), evening!!.done.map { it.id })
        assertEquals(listOf("t2"), evening.left.map { it.id })

        // Set aside: its due day goes, and no slot of the day is written, there being none.
        viewModel.chooseOutcome("t2", EveningOutcome.ASIDE)
        advanceUntilIdle()

        coVerify { taskRepository.setDueDate("t2", null) }
        coVerify(exactly = 0) { taskRepository.setDaySlots(today, any(), any()) }
        assertEquals(EveningOutcome.ASIDE, viewModel.uiState.value.evening?.outcomeOf("t2"))
        assertEquals(listOf("t1"), viewModel.uiState.value.evening?.todayIds)
    }

    @Test
    fun `the evening does not open before the evening time`() = runTest {
        val viewModel = openEvening(now = LocalTime.of(20, 59))

        assertNull(viewModel.uiState.value.evening)
    }

    @Test
    fun `the evening does not open once Bonne nuit was said`() = runTest {
        val viewModel = openEvening(reviewedAt = "2026-10-15T19:10:00Z")

        assertNull(viewModel.uiState.value.evening)
    }

    @Test
    fun `past midnight, the gestures and Bonne nuit still write the day reviewed and the next one`() = runTest {
        coEvery { taskRepository.saveDayReview(today, any()) } returns
            Result.success(DayReview(id = "rv", date = today.toString(), reviewedAt = "2026-10-15T23:10:00Z"))
        val viewModel = openEvening()
        clockDay = today.plusDays(1)

        viewModel.chooseOutcome("d4", EveningOutcome.TOMORROW)
        advanceUntilIdle()
        viewModel.chooseOutcome("d5", EveningOutcome.ASIDE)
        advanceUntilIdle()
        viewModel.goodNight()
        advanceUntilIdle()

        assertEquals(listOf(listOf("d4")), tomorrowPuts)
        assertEquals(listOf(listOf("d1", "d4", "d2", "d3")), todayPuts)
        coVerify(exactly = 1) { taskRepository.saveDayReview(today, emptyList()) }
    }

    @Test
    fun `a set-aside that fails halfway shows the task in Un jour and says so`() = runTest {
        val viewModel = openEvening()
        coEvery { taskRepository.setDaySlots(today, any(), true) } returns Result.failure(IllegalStateException("offline"))

        viewModel.chooseOutcome("d5", EveningOutcome.ASIDE)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(EveningOutcome.ASIDE, state.evening!!.outcomeOf("d5"))
        assertEquals("Impossible de modifier la tâche.", state.message)
    }

    @Test
    fun `Demain writes the task in tomorrow's five without settling it`() = runTest {
        val viewModel = openEvening(tomorrowTasks = listOf(photos))

        viewModel.chooseOutcome("d4", EveningOutcome.TOMORROW)
        advanceUntilIdle()

        assertEquals(listOf(listOf("r1", "d4")), tomorrowPuts)
        val state = viewModel.uiState.value.evening!!
        assertEquals(EveningOutcome.TOMORROW, state.outcomeOf("d4"))
        assertEquals(listOf("r1", "d4"), state.tomorrowIds)
        assertTrue(todayPuts.isEmpty())
    }

    @Test
    fun `Plus tard after Demain takes the task out of tomorrow again`() = runTest {
        val viewModel = openEvening(tomorrowTasks = listOf(photos))
        viewModel.chooseOutcome("d4", EveningOutcome.TOMORROW)
        advanceUntilIdle()

        viewModel.chooseOutcome("d4", EveningOutcome.LATER)
        advanceUntilIdle()

        assertEquals(listOf(listOf("r1", "d4"), listOf("r1")), tomorrowPuts)
        assertEquals(EveningOutcome.LATER, viewModel.uiState.value.evening!!.outcomeOf("d4"))
    }

    @Test
    fun `Demain beyond five is refused - the way out stays Plus tard`() = runTest {
        val viewModel = openEvening(tomorrowTasks = listOf(form, call, bins, photos, books))

        viewModel.chooseOutcome("d4", EveningOutcome.TOMORROW)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(EveningOutcome.LATER, state.evening!!.outcomeOf("d4"))
        assertFalse(state.evening!!.canGoTomorrow("d4"))
        assertEquals("Demain a déjà 5 tâches.", state.message)
        assertTrue(tomorrowPuts.isEmpty())
    }

    @Test
    fun `a task already in tomorrow's five comes out Demain`() = runTest {
        val viewModel = openEvening(tomorrowTasks = listOf(plants))

        assertEquals(EveningOutcome.TOMORROW, viewModel.uiState.value.evening!!.outcomeOf("d4"))
    }

    @Test
    fun `Mettre de côté clears the due day and takes the task out of today, Annuler puts both back`() = runTest {
        val viewModel = openEvening()

        viewModel.chooseOutcome("d5", EveningOutcome.ASIDE)
        advanceUntilIdle()

        coVerify(exactly = 1) { taskRepository.setDueDate("d5", null) }
        assertEquals(listOf(listOf("d1", "d4", "d2", "d3")), todayPuts)
        var state = viewModel.uiState.value.evening!!
        assertEquals(EveningOutcome.ASIDE, state.outcomeOf("d5"))
        assertEquals(5, state.day.total)

        viewModel.chooseOutcome("d5", EveningOutcome.LATER)
        advanceUntilIdle()

        coVerify(exactly = 1) { taskRepository.setDueDate("d5", "2026-10-17") }
        assertEquals(listOf("d1", "d4", "d2", "d5", "d3"), todayPuts.last())
        state = viewModel.uiState.value.evening!!
        assertEquals(EveningOutcome.LATER, state.outcomeOf("d5"))
        assertEquals(listOf("d1", "d4", "d2", "d5", "d3"), state.todayIds)
    }

    @Test
    fun `a gesture that fails keeps its way out and says so`() = runTest {
        val viewModel = openEvening()
        coEvery { taskRepository.setDueDate("d5", null) } returns Result.failure(IllegalStateException("offline"))

        viewModel.chooseOutcome("d5", EveningOutcome.ASIDE)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(EveningOutcome.LATER, state.evening!!.outcomeOf("d5"))
        assertFalse(state.evening!!.isSaving)
        assertEquals("Impossible de modifier la tâche.", state.message)
        assertTrue(todayPuts.isEmpty())
    }

    @Test
    fun `what helped is ticked and unticked, and Bonne nuit records it in the list's order and closes`() = runTest {
        val viewModel = openEvening()
        coEvery { taskRepository.saveDayReview(today, any()) } returns
            Result.success(DayReview(id = "rv", date = today.toString(), reviewedAt = "2026-10-15T19:10:00Z"))

        viewModel.toggleHelped(DayReview.TIMER)
        viewModel.toggleHelped(DayReview.START_SMALL)
        viewModel.toggleHelped(DayReview.MORNING)
        viewModel.toggleHelped(DayReview.MORNING)
        assertEquals(setOf("timer", "start_small"), viewModel.uiState.value.evening!!.helped)

        viewModel.goodNight()
        advanceUntilIdle()

        coVerify(exactly = 1) { taskRepository.saveDayReview(today, listOf("start_small", "timer")) }
        assertNull(viewModel.uiState.value.evening)
        assertNotNull(viewModel.uiState.value.day)
    }

    @Test
    fun `a Bonne nuit that fails keeps the review open and says so`() = runTest {
        val viewModel = openEvening()
        coEvery { taskRepository.saveDayReview(today, any()) } returns Result.failure(IllegalStateException("offline"))

        viewModel.goodNight()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNotNull(state.evening)
        assertFalse(state.evening!!.isSaving)
        assertEquals("Impossible d'enregistrer le bilan.", state.message)
    }

    @Test
    fun `Plus tard closes the evening without recording it, and it does not open again by itself`() = runTest {
        val viewModel = openEvening()

        viewModel.dismissEvening()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.evening)
        coVerify(exactly = 0) { taskRepository.saveDayReview(any(), any()) }

        viewModel.refresh()
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.evening)

        viewModel.openEvening()
        advanceUntilIdle()
        assertNotNull("« Le soir » on the day opens it on demand", viewModel.uiState.value.evening)
    }

    @Test
    fun `restoreAt puts a task back where it stood among those still there`() {
        val original = listOf("a", "b", "c", "d", "e")
        assertEquals(listOf("a", "b", "c", "d", "e"), restoreAt(listOf("a", "b", "d", "e"), "c", original))
        assertEquals(listOf("a", "b"), restoreAt(listOf("b"), "a", original))
        assertEquals(listOf("a", "d", "e"), restoreAt(listOf("a", "e"), "d", original))
        assertEquals(listOf("a", "c"), restoreAt(listOf("a", "c"), "c", original))
    }

    // La constellation et le palier (MAG-509)

    private fun starsOf(month: String, count: Int) = Stars(month = month, count = count, total = 20, best = null, latestAt = null)

    private val palierPush = """{"@id":"/api/rewards/rw9","subjectType":"saga_chapter","subjectId":"c1","level":"palier","surprise":null,""" +
        """"messageKey":"palier-doux.1","message":"Un chapitre de bouclé. Prends le temps d'en profiter.","title":"Chapitre 1","star":false,""" +
        """"earnedAt":"2026-10-15T20:00:00+00:00"}"""

    @Test
    fun `the evening reads the month's constellation, the current month being its limit (MAG-509)`() = runTest {
        coEvery { taskRepository.getStars(null) } returns Result.success(starsOf("2026-10", 5))
        val viewModel = openEvening()

        val state = viewModel.uiState.value.evening!!
        assertEquals(5, state.stars?.count)
        assertEquals("2026-10", state.currentMonth)
        assertFalse("nothing after the current month", state.canGoNextMonth)

        viewModel.nextMonth()
        advanceUntilIdle()
        coVerify(exactly = 0) { taskRepository.getStars("2026-11") }
        assertEquals("2026-10", viewModel.uiState.value.evening!!.stars?.month)
    }

    @Test
    fun `the previous month shows its constellation, the next one brings the current month back (MAG-509)`() = runTest {
        coEvery { taskRepository.getStars(null) } returns Result.success(starsOf("2026-10", 5))
        coEvery { taskRepository.getStars("2026-09") } returns Result.success(starsOf("2026-09", 7))
        val viewModel = openEvening()

        viewModel.previousMonth()
        advanceUntilIdle()
        val back = viewModel.uiState.value.evening!!
        assertEquals(7, back.stars?.count)
        assertEquals("Ta constellation de septembre · 7 étoiles", back.stars?.title)
        assertTrue(back.canGoNextMonth)
        assertEquals("the current month stays the limit", "2026-10", back.currentMonth)

        coEvery { taskRepository.getStars("2026-10") } returns Result.success(starsOf("2026-10", 5))
        viewModel.nextMonth()
        advanceUntilIdle()
        assertEquals(5, viewModel.uiState.value.evening!!.stars?.count)
        assertFalse(viewModel.uiState.value.evening!!.canGoNextMonth)
    }

    @Test
    fun `a month that cannot be read leaves the constellation shown as it was (MAG-509)`() = runTest {
        coEvery { taskRepository.getStars(null) } returns Result.success(starsOf("2026-10", 5))
        val viewModel = openEvening()

        viewModel.previousMonth()
        advanceUntilIdle()

        assertEquals("2026-10", viewModel.uiState.value.evening!!.stars?.month)
    }

    @Test
    fun `an evening whose stars cannot be read opens without a constellation (MAG-509)`() = runTest {
        val viewModel = openEvening()

        assertNotNull(viewModel.uiState.value.evening)
        assertNull(viewModel.uiState.value.evening!!.stars)
    }

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
        coVerify(exactly = 1) { taskRepository.getDayPlan(today) }

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

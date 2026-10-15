package com.maggie.app.ui.screens.tasks

import com.maggie.app.data.api.TaskCreateRequest
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureEvent
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.mercure.MercureTopics
import com.maggie.app.data.model.Task
import com.maggie.app.data.model.TaskView
import com.maggie.app.data.model.TaskViewCounts
import com.maggie.app.data.model.TaskViewResponse
import com.maggie.app.data.repository.TaskRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
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

@OptIn(ExperimentalCoroutinesApi::class)
class TasksViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var taskRepository: TaskRepository
    private lateinit var mercureService: MercureService
    private lateinit var authRepository: AuthRepository
    private val pushes = MutableSharedFlow<MercureEvent>()

    private val counts = TaskViewCounts(today = 2, upcoming = 1, someday = 1, done = 1)
    private val late = task("t1", "Renvoyer le formulaire", dueDate = "2026-10-13", overdueDays = 2)
    private val canteen = task("t2", "Payer la cantine", dueDate = "2026-10-15")

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        taskRepository = mockk()
        mercureService = mockk()
        authRepository = mockk(relaxed = true)
        every { authRepository.token } returns flowOf("test-jwt")
        coEvery { authRepository.getUserId() } returns "u1"
        every { mercureService.subscribe(MercureTopics.userScoped("u1", MercureTopics.TASKS)) } returns pushes
        serve(TaskView.TODAY, late, canteen)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel() =
        TasksViewModel(taskRepository, mercureService, authRepository, today = { LocalDate.of(2026, 10, 15) })

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
        assertTrue(state.tasks.isEmpty())
        assertNull(state.counts)
    }

    @Test
    fun `opening the screen lands the view's tasks and the counts of every tab in the state`() = runTest {
        val viewModel = createViewModel()
        viewModel.onOpened()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf("t1", "t2"), state.tasks.map { it.id })
        assertEquals(counts, state.counts)
        assertFalse(state.isLoading)
        assertNull(state.error)
        assertFalse(state.isEmpty)
    }

    @Test
    fun `a view with no task is empty, not loading and not in error`() = runTest {
        serve(TaskView.TODAY)

        val viewModel = createViewModel()
        viewModel.onOpened()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isEmpty)
        assertFalse(viewModel.uiState.value.isLoading)
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
    fun `a refresh that fails under a list keeps the list and says so in a message`() = runTest {
        val viewModel = createViewModel()
        viewModel.onOpened()
        advanceUntilIdle()

        coEvery { taskRepository.getTaskView(TaskView.TODAY, null) } returns Result.failure(RuntimeException("Offline"))
        viewModel.refresh()
        advanceUntilIdle()

        assertEquals(2, viewModel.uiState.value.tasks.size)
        assertNull(viewModel.uiState.value.error)
        assertEquals("Offline", viewModel.uiState.value.message)
    }

    @Test
    fun `a refresh keeps the list on screen while it reads`() = runTest {
        val viewModel = createViewModel()
        viewModel.onOpened()
        advanceUntilIdle()

        val answer = CompletableDeferred<Result<TaskViewResponse>>()
        coEvery { taskRepository.getTaskView(TaskView.TODAY, null) } coAnswers { answer.await() }
        viewModel.refresh()
        runCurrent()

        assertEquals(2, viewModel.uiState.value.tasks.size)
        assertFalse(viewModel.uiState.value.isLoading)
        answer.complete(Result.success(response(canteen)))
        advanceUntilIdle()
        assertEquals(listOf("t2"), viewModel.uiState.value.tasks.map { it.id })
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
        slowToday.complete(Result.success(response(late, canteen)))
        advanceUntilIdle()

        assertEquals(listOf("t4"), viewModel.uiState.value.tasks.map { it.id })
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
        val viewModel = createViewModel()
        viewModel.onOpened()
        advanceUntilIdle()

        serve(TaskView.TODAY, canteen)
        viewModel.toggleDone(late)
        advanceUntilIdle()

        coVerify { taskRepository.toggleDone("t1", true) }
        assertEquals(listOf("t2"), viewModel.uiState.value.tasks.map { it.id })
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
        val viewModel = createViewModel()
        viewModel.onOpened()
        advanceUntilIdle()

        viewModel.toggleDone(late)
        advanceUntilIdle()

        assertEquals(2, viewModel.uiState.value.tasks.size)
        assertNotNull(viewModel.uiState.value.message)
        viewModel.dismissMessage()
        assertNull(viewModel.uiState.value.message)
    }

    /** The request sent when «Acheter du pain» is added from [view]. */
    private fun TestScope.addFrom(view: TaskView): TaskCreateRequest {
        val request = slot<TaskCreateRequest>()
        coEvery { taskRepository.createTask(capture(request)) } returns Result.success(task("new", "x"))
        serve(view)
        val viewModel = createViewModel()
        viewModel.selectView(view)
        advanceUntilIdle()
        viewModel.onDraftChange("  Acheter du pain  ")
        viewModel.addTask()
        advanceUntilIdle()
        assertEquals("", viewModel.uiState.value.draft)
        assertFalse(viewModel.uiState.value.isAdding)
        return request.captured
    }

    @Test
    fun `a task added in Aujourd'hui is due today`() = runTest {
        val request = addFrom(TaskView.TODAY)

        assertEquals("Acheter du pain", request.title)
        assertEquals("2026-10-15", request.dueDate)
    }

    @Test
    fun `a task added in À venir is due tomorrow`() = runTest {
        assertEquals("2026-10-16", addFrom(TaskView.UPCOMING).dueDate)
    }

    @Test
    fun `a task added in À faire un jour has no due day`() = runTest {
        assertNull(addFrom(TaskView.SOMEDAY).dueDate)
    }

    @Test
    fun `adding reads the view again so the task shows up`() = runTest {
        coEvery { taskRepository.createTask(any()) } returns Result.success(task("new", "Acheter du pain", dueDate = "2026-10-15"))
        val viewModel = createViewModel()
        viewModel.onOpened()
        advanceUntilIdle()

        serve(TaskView.TODAY, late, canteen, task("new", "Acheter du pain", dueDate = "2026-10-15"))
        viewModel.onDraftChange("Acheter du pain")
        viewModel.addTask()
        advanceUntilIdle()

        assertEquals(3, viewModel.uiState.value.tasks.size)
    }

    @Test
    fun `a blank draft, or one in Faites, adds nothing`() = runTest {
        val viewModel = createViewModel()
        viewModel.onOpened()
        advanceUntilIdle()

        viewModel.onDraftChange("   ")
        viewModel.addTask()
        serve(TaskView.DONE)
        viewModel.selectView(TaskView.DONE)
        advanceUntilIdle()
        viewModel.onDraftChange("Une tâche")
        viewModel.addTask()
        advanceUntilIdle()

        coVerify(exactly = 0) { taskRepository.createTask(any()) }
        assertEquals("Une tâche", viewModel.uiState.value.draft)
    }

    @Test
    fun `a failed add keeps the draft so nothing typed is lost`() = runTest {
        coEvery { taskRepository.createTask(any()) } returns Result.failure(RuntimeException("boom"))
        val viewModel = createViewModel()
        viewModel.onOpened()
        advanceUntilIdle()

        viewModel.onDraftChange("Acheter du pain")
        viewModel.addTask()
        advanceUntilIdle()

        assertEquals("Acheter du pain", viewModel.uiState.value.draft)
        assertFalse(viewModel.uiState.value.isAdding)
        assertNotNull(viewModel.uiState.value.message)
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
        val viewModel = createViewModel()
        viewModel.onOpened()
        advanceUntilIdle()

        serve(TaskView.TODAY, canteen)
        pushes.emit(MercureEvent())
        advanceUntilIdle()

        assertEquals(listOf("t2"), viewModel.uiState.value.tasks.map { it.id })
    }
}

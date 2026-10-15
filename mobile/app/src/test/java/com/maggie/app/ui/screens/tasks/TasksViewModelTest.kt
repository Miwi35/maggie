package com.maggie.app.ui.screens.tasks

import com.maggie.app.data.api.TaskCreateRequest
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureEvent
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.mercure.MercureTopics
import com.maggie.app.data.api.ApiRefusalException
import com.maggie.app.data.model.DayPlan
import com.maggie.app.data.model.DaySlot
import com.maggie.app.data.model.DayTask
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

@OptIn(ExperimentalCoroutinesApi::class)
class TasksViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var taskRepository: TaskRepository
    private lateinit var mercureService: MercureService
    private lateinit var authRepository: AuthRepository
    private val pushes = MutableSharedFlow<MercureEvent>()
    private val dayPushes = MutableSharedFlow<MercureEvent>()

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
        mercureService = mockk()
        authRepository = mockk(relaxed = true)
        every { authRepository.token } returns flowOf("test-jwt")
        coEvery { authRepository.getUserId() } returns "u1"
        every { mercureService.subscribe(MercureTopics.userScoped("u1", MercureTopics.TASKS)) } returns pushes
        every { mercureService.subscribe(MercureTopics.userScoped("u1", MercureTopics.DAY_PLANS)) } returns dayPushes
        serve(TaskView.TODAY, late, canteen)
        serveDay(day(listOf(form, call), reserve = listOf(photos, books)))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel() =
        TasksViewModel(taskRepository, mercureService, authRepository, today = { today })

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

    /** The day loaded, and the PUTs it receives captured in [puts], answered with [answer] of the list sent. */
    private fun TestScope.openDay(
        puts: MutableList<List<String>> = mutableListOf(),
        answer: (List<String>) -> Result<DayPlan> = { ids -> Result.success(dayOf(ids)) },
    ): TasksViewModel {
        coEvery { taskRepository.setDaySlots(today, capture(puts)) } answers { answer(puts.last()) }
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
    fun `opening the screen lands the day and the counts of every tab in the state`() = runTest {
        val viewModel = createViewModel()
        viewModel.onOpened()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf("d1", "d2"), state.day?.taskIds)
        assertEquals("d1", state.day?.now?.task?.id)
        assertEquals(listOf("r1", "r2"), state.day?.reserve?.map { it.id })
        assertEquals(counts, state.counts)
        assertFalse(state.isLoading)
        assertNull(state.error)
        assertFalse(state.isEmpty)
        assertFalse("The reserve is folded until asked for", state.isReserveOpen)
    }

    @Test
    fun `the Aujourd'hui tab counts what is left of the day, the others their view`() = runTest {
        serveDay(day(listOf(form.copy(completedAt = "2026-10-15T08:00:00Z"), call, bins)))
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
    fun `an empty day is not an empty tab, its add field and its reserve stay`() = runTest {
        serveDay(day(emptyList(), reserve = listOf(photos)))

        val viewModel = createViewModel()
        viewModel.onOpened()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isEmpty)
        assertNull(viewModel.uiState.value.day?.now)
    }

    @Test
    fun `a failing read exposes the error and stops loading`() = runTest {
        coEvery { taskRepository.getDayPlan(today) } returns Result.failure(RuntimeException("Network error"))

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

        coEvery { taskRepository.getDayPlan(today) } returns Result.failure(RuntimeException("Offline"))
        viewModel.refresh()
        advanceUntilIdle()

        assertEquals(2, viewModel.uiState.value.day?.total)
        assertNull(viewModel.uiState.value.error)
        assertEquals("Offline", viewModel.uiState.value.message)
    }

    @Test
    fun `a refresh keeps the day on screen while it reads`() = runTest {
        val viewModel = createViewModel()
        viewModel.onOpened()
        advanceUntilIdle()

        val answer = CompletableDeferred<Result<DayPlan>>()
        coEvery { taskRepository.getDayPlan(today) } coAnswers { answer.await() }
        viewModel.refresh()
        runCurrent()

        assertEquals(2, viewModel.uiState.value.day?.total)
        assertFalse(viewModel.uiState.value.isLoading)
        answer.complete(Result.success(day(listOf(call))))
        advanceUntilIdle()
        assertEquals(listOf("d2"), viewModel.uiState.value.day?.taskIds)
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
        val slowToday = CompletableDeferred<Result<DayPlan>>()
        coEvery { taskRepository.getDayPlan(today) } coAnswers { slowToday.await() }
        serve(TaskView.SOMEDAY, task("t4", "Trier les photos"))
        val viewModel = createViewModel()
        viewModel.onOpened()
        runCurrent()

        viewModel.selectView(TaskView.SOMEDAY)
        advanceUntilIdle()
        slowToday.complete(Result.success(day(listOf(form))))
        advanceUntilIdle()

        assertEquals(listOf("t4"), viewModel.uiState.value.tasks.map { it.id })
        assertNull(viewModel.uiState.value.day)
    }

    @Test
    fun `selecting the tab already open reads nothing again`() = runTest {
        val viewModel = createViewModel()
        viewModel.onOpened()
        advanceUntilIdle()

        viewModel.selectView(TaskView.TODAY)
        advanceUntilIdle()

        coVerify(exactly = 1) { taskRepository.getDayPlan(today) }
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

    /** The request sent when «Acheter du pain» is added from [view]. */
    private fun TestScope.addFrom(view: TaskView): TaskCreateRequest {
        val request = slot<TaskCreateRequest>()
        coEvery { taskRepository.createTask(capture(request)) } returns Result.success(task("new", "x"))
        coEvery { taskRepository.setDaySlots(today, any()) } returns Result.success(day(listOf(form, call)))
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
    fun `a task added in Aujourd'hui takes a slot, not a due day`() = runTest {
        val request = addFrom(TaskView.TODAY)

        assertEquals("Acheter du pain", request.title)
        assertNull(request.dueDate)
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
        coEvery { taskRepository.createTask(any()) } returns Result.success(task("new", "Acheter du pain", dueDate = "2026-10-16"))
        serve(TaskView.UPCOMING, late, canteen)
        val viewModel = createViewModel()
        viewModel.selectView(TaskView.UPCOMING)
        advanceUntilIdle()

        serve(TaskView.UPCOMING, late, canteen, task("new", "Acheter du pain", dueDate = "2026-10-16"))
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

        serveDay(day(listOf(call, bins), reserve = listOf(form)))
        dayPushes.emit(MercureEvent())
        advanceUntilIdle()

        assertEquals(listOf("d2", "d3"), viewModel.uiState.value.day?.taskIds)
        assertEquals(1, dayPushes.subscriptionCount.value)
    }

    @Test
    fun `a task ticked elsewhere reads the day again`() = runTest {
        val viewModel = openDay()

        serveDay(day(listOf(form.copy(completedAt = "2026-10-15T08:00:00Z"), call)))
        pushes.emit(MercureEvent())
        advanceUntilIdle()

        assertEquals("d2", viewModel.uiState.value.day?.now?.task?.id)
    }

    // --- The day (MAG-497) ---

    @Test
    fun `ticking « Maintenant » moves it to the next slot at once, then reads the day again`() = runTest {
        coEvery { taskRepository.toggleDone("d1", true) } returns Result.success(task("d1", "Renvoyer le formulaire"))
        val viewModel = openDay()

        serveDay(day(listOf(form.copy(completedAt = "2026-10-15T08:00:00Z"), call)))
        viewModel.toggleDayTask(form)
        // Before the server answered: the ring and « Maintenant » already moved.
        assertEquals("d2", viewModel.uiState.value.day?.now?.task?.id)
        assertEquals(1, viewModel.uiState.value.day?.done)
        advanceUntilIdle()

        coVerify { taskRepository.toggleDone("d1", true) }
        assertEquals("d2", viewModel.uiState.value.day?.now?.task?.id)
        assertEquals(1, viewModel.uiState.value.day?.done)
        assertEquals("Aujourd'hui (1)", tabLabelOf(TaskView.TODAY, viewModel.uiState.value))
    }

    @Test
    fun `unticking a done slot asks for completedAt null and brings « Maintenant » back to it`() = runTest {
        val doneForm = form.copy(completedAt = "2026-10-15T08:00:00Z")
        serveDay(day(listOf(doneForm, call)))
        coEvery { taskRepository.toggleDone("d1", false) } returns Result.success(task("d1", "Renvoyer le formulaire"))
        val viewModel = openDay()
        assertEquals("d2", viewModel.uiState.value.day?.now?.task?.id)

        serveDay(day(listOf(form, call)))
        viewModel.toggleDayTask(doneForm)
        advanceUntilIdle()

        coVerify { taskRepository.toggleDone("d1", false) }
        assertEquals("d1", viewModel.uiState.value.day?.now?.task?.id)
        assertEquals(0, viewModel.uiState.value.day?.done)
    }

    @Test
    fun `a tick of the day that fails puts the day back and says so`() = runTest {
        coEvery { taskRepository.toggleDone("d1", true) } returns Result.failure(RuntimeException("boom"))
        val viewModel = openDay()

        viewModel.toggleDayTask(form)
        advanceUntilIdle()

        assertEquals("d1", viewModel.uiState.value.day?.now?.task?.id)
        assertEquals(0, viewModel.uiState.value.day?.done)
        assertNotNull(viewModel.uiState.value.message)
    }

    @Test
    fun `the reserve unfolds and folds back`() = runTest {
        val viewModel = openDay()

        viewModel.toggleReserve()
        assertTrue(viewModel.uiState.value.isReserveOpen)
        viewModel.toggleReserve()
        assertFalse(viewModel.uiState.value.isReserveOpen)
    }

    @Test
    fun `a reserve task put in the day takes the next free slot`() = runTest {
        val puts = mutableListOf<List<String>>()
        val viewModel = openDay(puts)

        viewModel.putInDay("r1")
        assertTrue(viewModel.uiState.value.isSavingDay)
        advanceUntilIdle()

        assertEquals(listOf(listOf("d1", "d2", "r1")), puts)
        assertEquals(listOf("d1", "d2", "r1"), viewModel.uiState.value.day?.taskIds)
        assertEquals(listOf("d3", "d4", "d5", "r2"), viewModel.uiState.value.day?.reserve?.map { it.id })
        assertFalse(viewModel.uiState.value.isSavingDay)
    }

    @Test
    fun `when the five are taken a reserve task replaces the slot chosen, which goes back to the reserve`() = runTest {
        serveDay(dayOf(listOf("d1", "d2", "d3", "d4", "d5")))
        val puts = mutableListOf<List<String>>()
        val viewModel = openDay(puts)

        viewModel.putInDay("r1", insteadOf = "d3")
        advanceUntilIdle()

        assertEquals(listOf(listOf("d1", "d2", "r1", "d4", "d5")), puts)
        assertEquals(listOf("d1", "d2", "r1", "d4", "d5"), viewModel.uiState.value.day?.taskIds)
        assertTrue(viewModel.uiState.value.day!!.reserve.any { it.id == "d3" })
    }

    @Test
    fun `a full day and no slot named sends nothing`() = runTest {
        serveDay(dayOf(listOf("d1", "d2", "d3", "d4", "d5")))
        val puts = mutableListOf<List<String>>()
        val viewModel = openDay(puts)

        viewModel.putInDay("r1")
        advanceUntilIdle()

        assertTrue(puts.isEmpty())
    }

    @Test
    fun `« Remettre dans la réserve » frees the slot and the ones after it move up`() = runTest {
        serveDay(dayOf(listOf("d1", "d2", "d3")))
        val puts = mutableListOf<List<String>>()
        val viewModel = openDay(puts)

        viewModel.removeFromDay("d2")
        advanceUntilIdle()

        assertEquals(listOf(listOf("d1", "d3")), puts)
        assertEquals(listOf("d1", "d3"), viewModel.uiState.value.day?.taskIds)
        assertTrue(viewModel.uiState.value.day!!.reserve.any { it.id == "d2" })
    }

    @Test
    fun `a refused change of the day says so and keeps the day as it was`() = runTest {
        val viewModel = openDay(answer = { Result.failure(ApiRefusalException(400, "A day holds 5 slots at most")) })

        viewModel.putInDay("r1")
        advanceUntilIdle()

        assertEquals(listOf("d1", "d2"), viewModel.uiState.value.day?.taskIds)
        assertEquals("Impossible de modifier la journée.", viewModel.uiState.value.message)
        assertFalse(viewModel.uiState.value.isSavingDay)
    }

    @Test
    fun `a task added in the day with a slot free takes it`() = runTest {
        coEvery { taskRepository.createTask(any()) } returns Result.success(task("d5", "Acheter du pain"))
        val puts = mutableListOf<List<String>>()
        val viewModel = openDay(puts)

        serveDay(dayOf(listOf("d1", "d2", "d5")))
        viewModel.onDraftChange("Acheter du pain")
        viewModel.addTask()
        advanceUntilIdle()

        assertEquals(listOf(listOf("d1", "d2", "d5")), puts)
        assertEquals(listOf("d1", "d2", "d5"), viewModel.uiState.value.day?.taskIds)
        assertEquals("", viewModel.uiState.value.draft)
    }

    @Test
    fun `a task added when the five are taken waits in the reserve, no slot asked for`() = runTest {
        serveDay(dayOf(listOf("d1", "d2", "d3", "d4", "d5")))
        coEvery { taskRepository.createTask(any()) } returns Result.success(task("r1", "Trier les photos"))
        val puts = mutableListOf<List<String>>()
        val viewModel = openDay(puts)

        viewModel.onDraftChange("Trier les photos")
        viewModel.addTask()
        advanceUntilIdle()

        assertTrue(puts.isEmpty())
        assertTrue(viewModel.uiState.value.day!!.reserve.any { it.id == "r1" })
        assertEquals("", viewModel.uiState.value.draft)
    }

    @Test
    fun `a task added whose slot is refused waits in the reserve and says so`() = runTest {
        coEvery { taskRepository.createTask(any()) } returns Result.success(task("d5", "Acheter du pain"))
        val viewModel = openDay(answer = { Result.failure(ApiRefusalException(400, "no")) })

        viewModel.onDraftChange("Acheter du pain")
        viewModel.addTask()
        advanceUntilIdle()

        assertEquals("La tâche attend dans la réserve.", viewModel.uiState.value.message)
        assertEquals("", viewModel.uiState.value.draft)
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
}

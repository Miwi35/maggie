package com.maggie.app.ui.screens.tasks

import com.maggie.app.data.model.DayPlan
import com.maggie.app.data.model.DaySlot
import com.maggie.app.data.model.DayTask
import com.maggie.app.data.model.Task
import com.maggie.app.data.repository.TaskRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/** The Focus (MAG-499): one test per action — start, pause, resume, end, petit pas, découper, plus tard, c'est fait. */
@OptIn(ExperimentalCoroutinesApi::class)
class FocusViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var repository: TaskRepository
    private val today = LocalDate.of(2026, 10, 15)

    private val focused = Task(id = "t1", title = "Appeler le garage", firstStep = "Trouver le numéro")
    private val stepOne = Task(id = "s1", title = "Chercher le numéro", parent = Task.iriOf("t1"), position = "1")
    private val stepTwo = Task(id = "s2", title = "Dire la plaque", parent = Task.iriOf("t1"), position = "2")

    private var now = 0L

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        repository = mockk()
        coEvery { repository.fetchTask("t1") } returns Result.success(focused)
        coEvery { repository.getSubtasks("t1") } returns Result.success(listOf(stepOne, stepTwo))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** The clock follows the test's virtual time, so the ticker and the timer agree. */
    private fun TestScope.viewModel() = FocusViewModel(repository, today = { today }, clock = { currentTime }, tickMs = 250)

    /** Time passes, and the tick due exactly at its end has run. */
    private fun TestScope.elapse(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    private fun TestScope.opened(): FocusViewModel {
        val viewModel = viewModel()
        viewModel.open("t1")
        advanceUntilIdle()
        return viewModel
    }

    private fun slot(position: Int, id: String, done: Boolean = false) =
        DaySlot(position, task = DayTask(id = id, title = id, completedAt = if (done) "2026-10-15T07:00:00Z" else null))

    private fun day(vararg slots: DaySlot) = DayPlan(
        id = "d",
        date = today.toString(),
        slots = slots.toList(),
        now = slots.firstOrNull { !it.task.isDone },
        total = slots.size,
    )

    @Test
    fun `opening reads the task, its first step and its petits pas, the timer waiting at ten minutes`() = runTest(testDispatcher) {
        val viewModel = opened()

        val state = viewModel.uiState.value
        assertEquals("Appeler le garage", state.task?.title)
        assertEquals("Trouver le numéro", state.task?.firstStep)
        assertEquals(listOf("s1", "s2"), state.steps.map { it.id })
        assertEquals(FocusTimerPhase.IDLE, state.phase)
        assertEquals(600_000L, state.remainingMs)
        assertFalse(state.isLoading)
    }

    @Test
    fun `opening a task that cannot be read says so and shows no timer`() = runTest(testDispatcher) {
        coEvery { repository.fetchTask("t1") } returns Result.failure(RuntimeException("boom"))

        val viewModel = opened()

        assertEquals("Impossible d'ouvrir la tâche.", viewModel.uiState.value.error)
        assertNull(viewModel.uiState.value.task)
    }

    @Test
    fun `opening the task already in focus keeps its timer, as a rotation does`() = runTest(testDispatcher) {
        val viewModel = opened()
        viewModel.start()
        elapse(60_000)

        viewModel.open("t1")
        runCurrent()

        assertEquals(FocusTimerPhase.RUNNING, viewModel.uiState.value.phase)
        assertEquals(540_000L, viewModel.uiState.value.remainingMs)
        coVerify(exactly = 1) { repository.fetchTask("t1") }
        viewModel.close()
    }

    @Test
    fun `start runs the timer down from 10 00`() = runTest(testDispatcher) {
        val viewModel = opened()

        viewModel.start()
        elapse(30_000)

        val state = viewModel.uiState.value
        assertEquals(FocusTimerPhase.RUNNING, state.phase)
        assertEquals(570_000L, state.remainingMs)
        viewModel.close()
    }

    @Test
    fun `pause freezes the time left`() = runTest(testDispatcher) {
        val viewModel = opened()
        viewModel.start()
        elapse(30_000)

        viewModel.pause()
        val frozen = viewModel.uiState.value.remainingMs
        elapse(120_000)

        assertEquals(FocusTimerPhase.PAUSED, viewModel.uiState.value.phase)
        assertEquals(frozen, viewModel.uiState.value.remainingMs)
        assertEquals(570_000L, frozen)
    }

    @Test
    fun `resume goes on from the time left`() = runTest(testDispatcher) {
        val viewModel = opened()
        viewModel.start()
        elapse(30_000)
        viewModel.pause()
        elapse(120_000)

        viewModel.resume()
        elapse(10_000)

        assertEquals(FocusTimerPhase.RUNNING, viewModel.uiState.value.phase)
        assertEquals(560_000L, viewModel.uiState.value.remainingMs)
        viewModel.close()
    }

    @Test
    fun `the timer ends at zero by itself and stops ticking`() = runTest(testDispatcher) {
        val viewModel = opened()
        viewModel.start()

        elapse(600_000)

        assertEquals(FocusTimerPhase.FINISHED, viewModel.uiState.value.phase)
        assertEquals(0L, viewModel.uiState.value.remainingMs)
    }

    @Test
    fun `after the end, restart gives ten more minutes`() = runTest(testDispatcher) {
        val viewModel = opened()
        viewModel.start()
        elapse(600_000)

        viewModel.restart()
        elapse(1_000)

        assertEquals(FocusTimerPhase.RUNNING, viewModel.uiState.value.phase)
        assertEquals(599_000L, viewModel.uiState.value.remainingMs)
        viewModel.close()
    }

    @Test
    fun `the time left is read from the clock when the screen comes back, whatever the ticks did`() = runTest(testDispatcher) {
        var clockMs = 0L
        val viewModel = FocusViewModel(repository, today = { today }, clock = { clockMs }, tickMs = 250)
        viewModel.open("t1")
        advanceUntilIdle()
        viewModel.start()

        clockMs = 4 * 60_000 // the app was away: no tick ran
        viewModel.refreshTimer()

        assertEquals(360_000L, viewModel.uiState.value.remainingMs)
        assertEquals(FocusTimerPhase.RUNNING, viewModel.uiState.value.phase)
        viewModel.close()
    }

    @Test
    fun `ticking a petit pas saves it done and keeps the task as it is`() = runTest(testDispatcher) {
        coEvery { repository.toggleDone("s1", true) } returns Result.success(stepOne.copy(completedAt = "2026-10-15T08:00:00Z"))
        val viewModel = opened()

        viewModel.toggleStep("s1", true)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.steps.first { it.id == "s1" }.isDone)
        assertFalse(state.steps.first { it.id == "s2" }.isDone)
        assertNull(state.task?.completedAt)
        assertFalse(state.leaves)
        coVerify(exactly = 1) { repository.toggleDone("s1", true) }
    }

    @Test
    fun `a petit pas that cannot be saved is left as it was, with a message`() = runTest(testDispatcher) {
        coEvery { repository.toggleDone("s1", true) } returns Result.failure(RuntimeException("boom"))
        val viewModel = opened()

        viewModel.toggleStep("s1", true)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.steps.first { it.id == "s1" }.isDone)
        assertEquals("Impossible de modifier le petit pas.", viewModel.uiState.value.message)
    }

    @Test
    fun `« Je bloque » adds a petit pas by hand, as a subtask put last`() = runTest(testDispatcher) {
        val added = Task(id = "s3", title = "Noter l'heure", parent = Task.iriOf("t1"), position = "3")
        coEvery { repository.createSubtask("t1", "Noter l'heure") } returns Result.success(added)
        val viewModel = opened()

        viewModel.addStep("  Noter l'heure ")
        advanceUntilIdle()

        assertEquals(listOf("s1", "s2", "s3"), viewModel.uiState.value.steps.map { it.id })
        coVerify { repository.createSubtask("t1", "Noter l'heure") }
    }

    @Test
    fun `a blank petit pas is not sent`() = runTest(testDispatcher) {
        val viewModel = opened()

        viewModel.addStep("   ")
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.createSubtask(any(), any()) }
    }

    @Test
    fun `« C'est fait » ticks the task and goes back to the day`() = runTest(testDispatcher) {
        coEvery { repository.toggleDone("t1", true) } returns Result.success(focused.copy(completedAt = "2026-10-15T08:00:00Z"))
        val viewModel = opened()
        viewModel.start()

        viewModel.complete()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.leaves)
        assertFalse(viewModel.uiState.value.isBusy)
        coVerify(exactly = 1) { repository.toggleDone("t1", true) }
    }

    @Test
    fun `an answer to « C'est fait » that comes after the Focus was closed does not leave the next one`() = runTest(testDispatcher) {
        coEvery { repository.toggleDone("t1", true) } returns Result.success(focused.copy(completedAt = "2026-10-15T08:00:00Z"))
        val viewModel = opened()

        viewModel.complete()
        viewModel.close()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.leaves)
        assertNull(viewModel.uiState.value.taskId)
    }

    @Test
    fun `« C'est fait » that fails stays on the Focus with a message`() = runTest(testDispatcher) {
        coEvery { repository.toggleDone("t1", true) } returns Result.failure(RuntimeException("boom"))
        val viewModel = opened()

        viewModel.complete()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.leaves)
        assertFalse(viewModel.uiState.value.isBusy)
        assertEquals("Impossible de modifier la tâche.", viewModel.uiState.value.message)
    }

    @Test
    fun `« Plus tard » sends the day with the task last of the slots to do, the done one staying put, then goes back`() = runTest(testDispatcher) {
        coEvery { repository.getDayPlan(today) } returns Result.success(
            day(slot(1, "t1"), slot(2, "b"), slot(3, "c", done = true), slot(4, "d")),
        )
        val sent = mutableListOf<List<String>>()
        coEvery { repository.setDaySlots(today, capture(sent)) } returns Result.success(day(slot(1, "b")))
        val viewModel = opened()

        viewModel.later()
        advanceUntilIdle()

        assertEquals(listOf(listOf("b", "d", "c", "t1")), sent)
        assertTrue(viewModel.uiState.value.leaves)
    }

    @Test
    fun `« Plus tard » on the only task left to do changes nothing and goes back`() = runTest(testDispatcher) {
        coEvery { repository.getDayPlan(today) } returns Result.success(day(slot(1, "t1"), slot(2, "b", done = true)))
        val viewModel = opened()

        viewModel.later()
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.setDaySlots(any(), any()) }
        assertTrue(viewModel.uiState.value.leaves)
    }

    @Test
    fun `« Plus tard » that fails stays on the Focus, without a reproach`() = runTest(testDispatcher) {
        coEvery { repository.getDayPlan(today) } returns Result.success(day(slot(1, "t1"), slot(2, "b")))
        coEvery { repository.setDaySlots(today, any()) } returns Result.failure(RuntimeException("boom"))
        val viewModel = opened()

        viewModel.later()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.leaves)
        assertFalse(viewModel.uiState.value.isBusy)
        assertEquals("Impossible de modifier la journée.", viewModel.uiState.value.message)
    }

    @Test
    fun `closing forgets the Focus, the timer stops and the next one starts clean`() = runTest(testDispatcher) {
        val viewModel = opened()
        viewModel.start()

        viewModel.close()

        assertNull(viewModel.uiState.value.taskId)
        assertEquals(FocusTimerPhase.IDLE, viewModel.uiState.value.phase)
        elapse(60_000)
        assertEquals(600_000L, viewModel.uiState.value.remainingMs)
    }
}

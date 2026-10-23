package com.maggie.app.ui.screens.home

import com.maggie.app.data.model.HomeDay
import com.maggie.app.data.model.Routine
import com.maggie.app.data.model.Task
import com.maggie.app.ui.screens.home.HomeTasksFakes.Companion.EMPTY_CARD
import com.maggie.app.ui.screens.home.HomeTasksFakes.Companion.TODAY_CARD
import com.maggie.app.ui.screens.home.HomeTasksFakes.Companion.TOMORROW_CARD
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
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

/**
 * The « Tâches » card of the Accueil (MAG-561): one test per transition — read, read refused, a task ticked,
 * refused, unticked, a routine ticked, refused, tomorrow (nothing tickable), a push, a tap while one is in flight.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TasksCardViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakes: HomeTasksFakes

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        fakes = HomeTasksFakes()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val doneTask = mockk<Task>(relaxed = true)

    private fun ticksWork() {
        coEvery { fakes.taskRepository.toggleDone(any(), any()) } returns Result.success(doneTask)
        coEvery { fakes.routineRepository.setChecked(any(), any(), any()) } returns Result.success(mockk<Routine>(relaxed = true))
    }

    @Test
    fun `before the first answer the card is loading, with nothing to show`() = runTest {
        val viewModel = fakes.viewModel()

        viewModel.show(HomeDay.TODAY)

        val state = viewModel.uiState.value
        assertTrue(state.isLoading)
        assertNull(state.card)
        assertNull(state.error)
    }

    @Test
    fun `loading to loaded - the card is the answer for today and the loading stops`() = runTest {
        val viewModel = fakes.viewModel()

        viewModel.show(HomeDay.TODAY)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals(TODAY_CARD, state.card)
        assertNull(state.error)
        assertTrue(state.canTick)
        coVerify(exactly = 1) { fakes.homeCards.state(HomeModules.TASKS, HomeDay.TODAY) }
    }

    @Test
    fun `an empty answer is a loaded card with nothing to say`() = runTest {
        fakes.answers(HomeDay.TODAY, EMPTY_CARD)
        val viewModel = fakes.viewModel()

        viewModel.show(HomeDay.TODAY)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.card!!.isEmpty)
    }

    @Test
    fun `a failed read leaves an error in words, never the raw one, and Réessayer reads again`() = runTest {
        coEvery { fakes.homeCards.state(HomeModules.TASKS, HomeDay.TODAY) } returns Result.failure(RuntimeException("HTTP 503 upstream"))
        val viewModel = fakes.viewModel()

        viewModel.show(HomeDay.TODAY)
        advanceUntilIdle()

        val failed = viewModel.uiState.value
        assertFalse(failed.isLoading)
        assertNull(failed.card)
        assertEquals(TasksCardViewModel.READ_FAILED, failed.error)

        fakes.answers(HomeDay.TODAY, TODAY_CARD)
        viewModel.retry()
        advanceUntilIdle()

        assertEquals(TODAY_CARD, viewModel.uiState.value.card)
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `a body the card cannot read is a failed read`() = runTest {
        coEvery { fakes.homeCards.state(HomeModules.TASKS, HomeDay.TODAY) } returns
            Result.success(kotlinx.serialization.json.buildJsonObject { put("tasks", kotlinx.serialization.json.JsonPrimitive("nope")) })
        val viewModel = fakes.viewModel()

        viewModel.show(HomeDay.TODAY)
        advanceUntilIdle()

        assertEquals(TasksCardViewModel.READ_FAILED, viewModel.uiState.value.error)
    }

    @Test
    fun `ticking a task shows it done at once, ring and late count moved, before the API answers`() = runTest {
        val answer = CompletableDeferred<Result<Task>>()
        coEvery { fakes.taskRepository.toggleDone("t1", true) } coAnswers { answer.await() }
        val viewModel = fakes.viewModel()
        viewModel.show(HomeDay.TODAY)
        advanceUntilIdle()

        viewModel.toggleTask("t1")
        runCurrent()

        val optimistic = viewModel.uiState.value
        assertTrue(optimistic.card!!.tasks.first { it.id == "t1" }.done)
        assertEquals(2, optimistic.card!!.done)
        assertEquals(4, optimistic.card!!.total)
        assertEquals(0, optimistic.card!!.overdue)
        assertEquals(setOf("t1"), optimistic.pendingTasks)
        coVerify(exactly = 1) { fakes.homeCards.state(HomeModules.TASKS, HomeDay.TODAY) }
        answer.complete(Result.success(doneTask))
    }

    @Test
    fun `a task ticked is sent to the API as done, then the card is read again`() = runTest {
        ticksWork()
        val viewModel = fakes.viewModel()
        viewModel.show(HomeDay.TODAY)
        advanceUntilIdle()
        fakes.answers(HomeDay.TODAY, TODAY_CARD.withTaskDone("t1", true).copy(overdue = 0))

        viewModel.toggleTask("t1")
        advanceUntilIdle()

        coVerify(exactly = 1) { fakes.taskRepository.toggleDone("t1", true) }
        coVerify(exactly = 2) { fakes.homeCards.state(HomeModules.TASKS, HomeDay.TODAY) }
        val state = viewModel.uiState.value
        assertTrue(state.card!!.tasks.first { it.id == "t1" }.done)
        assertEquals(2, state.card!!.done)
        assertTrue(state.pendingTasks.isEmpty())
        assertNull(state.message)
    }

    @Test
    fun `a task the API refuses is taken back and said in words`() = runTest {
        coEvery { fakes.taskRepository.toggleDone("t1", true) } returns Result.failure(RuntimeException("HTTP 500 boom"))
        val viewModel = fakes.viewModel()
        viewModel.show(HomeDay.TODAY)
        advanceUntilIdle()

        viewModel.toggleTask("t1")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(TODAY_CARD, state.card)
        assertEquals(TasksCardViewModel.TASK_FAILED, state.message)
        assertTrue(state.pendingTasks.isEmpty())
    }

    @Test
    fun `a done task is unticked with done false`() = runTest {
        ticksWork()
        val viewModel = fakes.viewModel()
        viewModel.show(HomeDay.TODAY)
        advanceUntilIdle()

        viewModel.toggleTask("t3")
        runCurrent()

        assertFalse(viewModel.uiState.value.card!!.tasks.first { it.id == "t3" }.done)
        assertEquals(0, viewModel.uiState.value.card!!.done)
        advanceUntilIdle()
        coVerify(exactly = 1) { fakes.taskRepository.toggleDone("t3", false) }
    }

    @Test
    fun `a second tap on a task still in flight is ignored`() = runTest {
        val answer = CompletableDeferred<Result<Task>>()
        coEvery { fakes.taskRepository.toggleDone(any(), any()) } coAnswers { answer.await() }
        val viewModel = fakes.viewModel()
        viewModel.show(HomeDay.TODAY)
        advanceUntilIdle()

        viewModel.toggleTask("t2")
        runCurrent()
        viewModel.toggleTask("t2")
        runCurrent()

        coVerify(exactly = 1) { fakes.taskRepository.toggleDone("t2", true) }
        answer.complete(Result.success(doneTask))
    }

    @Test
    fun `a push while a tick is in flight does not read the card until the tick is settled`() = runTest {
        val answer = CompletableDeferred<Result<Task>>()
        coEvery { fakes.taskRepository.toggleDone(any(), any()) } coAnswers { answer.await() }
        val viewModel = fakes.viewModel()
        viewModel.show(HomeDay.TODAY)
        advanceUntilIdle()
        viewModel.toggleTask("t2")
        runCurrent()

        fakes.pushes.emit(Unit)
        runCurrent()

        coVerify(exactly = 1) { fakes.homeCards.state(HomeModules.TASKS, HomeDay.TODAY) }
        assertTrue(viewModel.uiState.value.card!!.tasks.first { it.id == "t2" }.done)

        answer.complete(Result.success(doneTask))
        advanceUntilIdle()
        coVerify(exactly = 2) { fakes.homeCards.state(HomeModules.TASKS, HomeDay.TODAY) }
    }

    @Test
    fun `ticking a routine shows it checked at once and puts today's date to the API`() = runTest {
        ticksWork()
        val viewModel = fakes.viewModel()
        viewModel.show(HomeDay.TODAY)
        advanceUntilIdle()

        viewModel.toggleRoutine("r1")
        runCurrent()

        assertTrue(viewModel.uiState.value.card!!.routines.first { it.id == "r1" }.checked)
        assertEquals(setOf("r1"), viewModel.uiState.value.pendingRoutines)
        advanceUntilIdle()
        coVerify(exactly = 1) { fakes.routineRepository.setChecked("r1", LocalDate.of(2026, 10, 23), true) }
        assertTrue(viewModel.uiState.value.pendingRoutines.isEmpty())
    }

    @Test
    fun `a checked routine is unticked with checked false`() = runTest {
        ticksWork()
        val viewModel = fakes.viewModel()
        viewModel.show(HomeDay.TODAY)
        advanceUntilIdle()

        viewModel.toggleRoutine("r2")
        advanceUntilIdle()

        coVerify(exactly = 1) { fakes.routineRepository.setChecked("r2", LocalDate.of(2026, 10, 23), false) }
    }

    @Test
    fun `a routine the API refuses is taken back and said in words`() = runTest {
        coEvery { fakes.routineRepository.setChecked(any(), any(), any()) } returns Result.failure(RuntimeException("HTTP 400"))
        val viewModel = fakes.viewModel()
        viewModel.show(HomeDay.TODAY)
        advanceUntilIdle()

        viewModel.toggleRoutine("r1")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.card!!.routines.first { it.id == "r1" }.checked)
        assertEquals(TasksCardViewModel.ROUTINE_FAILED, state.message)
    }

    @Test
    fun `tomorrow is read for tomorrow and nothing on it can be ticked`() = runTest {
        ticksWork()
        val viewModel = fakes.viewModel()

        viewModel.show(HomeDay.TOMORROW)
        advanceUntilIdle()
        viewModel.toggleTask("t9")
        viewModel.toggleRoutine("r1")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(HomeDay.TOMORROW, state.day)
        assertEquals(TOMORROW_CARD, state.card)
        assertFalse(state.canTick)
        coVerify(exactly = 0) { fakes.taskRepository.toggleDone(any(), any()) }
        coVerify(exactly = 0) { fakes.routineRepository.setChecked(any(), any(), any()) }
    }

    @Test
    fun `changing the day drops the old card and reads the new one`() = runTest {
        val viewModel = fakes.viewModel()
        viewModel.show(HomeDay.TODAY)
        advanceUntilIdle()

        viewModel.show(HomeDay.TOMORROW)

        assertNull(viewModel.uiState.value.card)
        assertTrue(viewModel.uiState.value.isLoading)
        advanceUntilIdle()
        assertEquals(TOMORROW_CARD, viewModel.uiState.value.card)
    }

    @Test
    fun `a change announced on the topic reads the card again, the old one standing meanwhile`() = runTest {
        val viewModel = fakes.viewModel()
        viewModel.show(HomeDay.TODAY)
        advanceUntilIdle()
        val moved = TODAY_CARD.copy(done = 2, tasks = TODAY_CARD.tasks.map { if (it.id == "t2") it.copy(done = true) else it })
        fakes.answers(HomeDay.TODAY, moved)

        fakes.pushes.emit(Unit)
        runCurrent()

        assertFalse(viewModel.uiState.value.isLoading)
        advanceUntilIdle()
        assertEquals(moved, viewModel.uiState.value.card)
    }

    @Test
    fun `a failed refresh keeps the card on screen`() = runTest {
        val viewModel = fakes.viewModel()
        viewModel.show(HomeDay.TODAY)
        advanceUntilIdle()
        coEvery { fakes.homeCards.state(HomeModules.TASKS, HomeDay.TODAY) } returns Result.failure(RuntimeException("offline"))

        fakes.pushes.emit(Unit)
        advanceUntilIdle()

        assertEquals(TODAY_CARD, viewModel.uiState.value.card)
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `the message of a failed tick goes when it is dismissed`() = runTest {
        coEvery { fakes.taskRepository.toggleDone(any(), any()) } returns Result.failure(RuntimeException("x"))
        val viewModel = fakes.viewModel()
        viewModel.show(HomeDay.TODAY)
        advanceUntilIdle()
        viewModel.toggleTask("t1")
        advanceUntilIdle()
        assertNotNull(viewModel.uiState.value.message)

        viewModel.dismissMessage()

        assertNull(viewModel.uiState.value.message)
    }

    @Test
    fun `once the card is closed a push no longer reads it`() = runTest {
        val viewModel = fakes.viewModel()
        viewModel.show(HomeDay.TODAY)
        advanceUntilIdle()

        viewModel.onClosed()
        fakes.pushes.emit(Unit)
        advanceUntilIdle()

        coVerify(exactly = 1) { fakes.homeCards.state(HomeModules.TASKS, HomeDay.TODAY) }
    }
}

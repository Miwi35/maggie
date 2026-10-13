package com.maggie.app.ui.screens.shared

import com.maggie.app.data.model.Task
import com.maggie.app.data.repository.TaskRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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

/** The subtasks of the open task (MAG-404): one test per state transition. */
@OptIn(ExperimentalCoroutinesApi::class)
class SubtasksViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var taskRepository: TaskRepository

    private val parent = Task(id = "p1", title = "Préparer le déménagement", description = "Les cartons sont à la cave")
    private val truck = Task(id = "s1", title = "Réserver le camion", parent = "/api/tasks/p1", position = "00000000000000000000")
    private val bank = Task(id = "s2", title = "Changer d'adresse à la banque", parent = "/api/tasks/p1", position = "00000000000000000001")

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        taskRepository = mockk()
        coEvery { taskRepository.getSubtasks("p1") } returns Result.success(listOf(truck, bank))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private suspend fun loaded(): SubtasksViewModel = SubtasksViewModel(taskRepository).also { it.load(parent) }

    @Test
    fun `opening a task loads its subtasks in order`() = runTest {
        val viewModel = SubtasksViewModel(taskRepository)

        viewModel.load(parent)
        assertTrue(viewModel.uiState.value.isLoading)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("p1", state.parentId)
        assertEquals(listOf("Réserver le camion", "Changer d'adresse à la banque"), state.subtasks.map { it.title })
        assertFalse(state.isLoading)
        assertNull(state.error)
    }

    @Test
    fun `a subtask has no subtasks of its own and asks for none`() = runTest {
        val viewModel = SubtasksViewModel(taskRepository)

        viewModel.load(truck)
        advanceUntilIdle()

        assertEquals(SubtasksUiState(), viewModel.uiState.value)
        coVerify(exactly = 0) { taskRepository.getSubtasks(any()) }
    }

    @Test
    fun `a failing load exposes the error and stops loading`() = runTest {
        coEvery { taskRepository.getSubtasks("p1") } returns Result.failure(RuntimeException("Network error"))

        val viewModel = loaded()
        advanceUntilIdle()

        assertEquals("Network error", viewModel.uiState.value.error)
        assertFalse(viewModel.uiState.value.isLoading)
        assertTrue(viewModel.uiState.value.subtasks.isEmpty())
    }

    @Test
    fun `ticking a subtask completes that subtask only, the parent stays open`() = runTest {
        val ticked = truck.copy(completedAt = "2026-10-13T08:00:00+00:00")
        coEvery { taskRepository.toggleDone("s1", true) } returns Result.success(ticked)
        val viewModel = loaded()
        advanceUntilIdle()

        viewModel.toggleDone("s1", true)
        advanceUntilIdle()

        coVerify(exactly = 1) { taskRepository.toggleDone("s1", true) }
        coVerify(exactly = 0) { taskRepository.toggleDone("p1", any()) }
        assertEquals(listOf(true, false), viewModel.uiState.value.subtasks.map { it.isDone })
    }

    @Test
    fun `unticking a subtask reopens it`() = runTest {
        coEvery { taskRepository.getSubtasks("p1") } returns
            Result.success(listOf(truck.copy(completedAt = "2026-10-13T08:00:00+00:00"), bank))
        coEvery { taskRepository.toggleDone("s1", false) } returns Result.success(truck)
        val viewModel = loaded()
        advanceUntilIdle()

        viewModel.toggleDone("s1", false)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.subtasks.first().isDone)
    }

    @Test
    fun `adding a subtask posts it under the parent and lists it last`() = runTest {
        val boxes = Task(id = "s3", title = "Acheter des cartons", parent = "/api/tasks/p1", position = "00000000000000000002")
        coEvery { taskRepository.createSubtask("p1", "Acheter des cartons") } returns Result.success(boxes)
        val viewModel = loaded()
        advanceUntilIdle()

        viewModel.add("  Acheter des cartons ")
        advanceUntilIdle()

        coVerify(exactly = 1) { taskRepository.createSubtask("p1", "Acheter des cartons") }
        assertEquals(listOf("s1", "s2", "s3"), viewModel.uiState.value.subtasks.map { it.id })
    }

    @Test
    fun `a blank title adds nothing`() = runTest {
        val viewModel = loaded()
        advanceUntilIdle()

        viewModel.add("   ")
        advanceUntilIdle()

        coVerify(exactly = 0) { taskRepository.createSubtask(any(), any()) }
    }

    @Test
    fun `moving a subtask down moves it past the next one in one call`() = runTest {
        coEvery { taskRepository.moveSubtask(truck, bank) } returns Result.success(
            listOf(truck.copy(position = bank.position), bank.copy(position = truck.position)),
        )
        val viewModel = loaded()
        advanceUntilIdle()

        viewModel.moveDown("s1")
        advanceUntilIdle()

        coVerify(exactly = 1) { taskRepository.moveSubtask(truck, bank) }
        assertEquals(listOf("s2", "s1"), viewModel.uiState.value.subtasks.map { it.id })
    }

    @Test
    fun `moving a subtask up swaps its position with the previous one`() = runTest {
        coEvery { taskRepository.moveSubtask(bank, truck) } returns Result.success(
            listOf(bank.copy(position = truck.position), truck.copy(position = bank.position)),
        )
        val viewModel = loaded()
        advanceUntilIdle()

        viewModel.moveUp("s2")
        advanceUntilIdle()

        assertEquals(listOf("s2", "s1"), viewModel.uiState.value.subtasks.map { it.id })
    }

    @Test
    fun `the first subtask cannot move up, nor the last down`() = runTest {
        val viewModel = loaded()
        advanceUntilIdle()

        viewModel.moveUp("s1")
        viewModel.moveDown("s2")
        advanceUntilIdle()

        coVerify(exactly = 0) { taskRepository.moveSubtask(any(), any()) }
    }

    @Test
    fun `a failing move keeps the order and exposes the error`() = runTest {
        coEvery { taskRepository.moveSubtask(truck, bank) } returns Result.failure(RuntimeException("Serveur indisponible"))
        val viewModel = loaded()
        advanceUntilIdle()

        viewModel.moveDown("s1")
        advanceUntilIdle()

        assertEquals("Serveur indisponible", viewModel.uiState.value.error)
        assertEquals(listOf("s1", "s2"), viewModel.uiState.value.subtasks.map { it.id })
    }

    @Test
    fun `a failing tick leaves the subtask as it was and exposes the error`() = runTest {
        coEvery { taskRepository.toggleDone("s1", true) } returns Result.failure(RuntimeException("Network error"))
        val viewModel = loaded()
        advanceUntilIdle()

        viewModel.toggleDone("s1", true)
        advanceUntilIdle()

        assertEquals("Network error", viewModel.uiState.value.error)
        assertFalse(viewModel.uiState.value.subtasks.first().isDone)
    }
}

package com.maggie.app.ui.screens.bilan

import com.maggie.app.data.model.Event
import com.maggie.app.data.model.Task
import com.maggie.app.data.repository.EventRepository
import com.maggie.app.data.repository.TaskRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.Instant

/** The Journée step of the Bilan (MAG-752): « Demain » and « Plus tard » on the tasks left, and what a failure restores. */
@OptIn(ExperimentalCoroutinesApi::class)
class BilanJourneeViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    // Sunday 8 November 2026, 21:40 in Paris.
    private val now = Instant.parse("2026-11-08T20:40:00Z")
    private val tasks = mockk<TaskRepository>()
    private val events = mockk<EventRepository>()
    private val recorded = mutableListOf<Pair<String, String?>>()
    private val record: (String, String?) -> Unit = { id, choice -> recorded += id to choice }

    private val garage = Task(id = "t-1", title = "Appeler le garage", dueDate = "2026-11-08")
    private val papers = Task(id = "t-2", title = "Trier les papiers", dueDate = "2026-11-07")
    private val bread = Task(id = "t-3", title = "Payer la cantine", dueDate = "2026-11-08", completedAt = "2026-11-08T10:00:00Z")

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        coEvery { tasks.getTodayTasks() } returns listOf(garage, papers)
        coEvery { tasks.getCompletedBetween(any(), any(), any()) } returns listOf(bread)
        coEvery { tasks.setDueDate(any(), any()) } answers { Result.success(garage) }
        coEvery { events.loadPeriod(any(), any()) } returns Result.success(Unit)
        coEvery { events.observeEvents() } returns flowOf(
            listOf(
                Event("e-1", "Point équipe", startAt = "2026-11-08T09:00:00+01:00", endAt = "2026-11-08T10:00:00+01:00"),
                Event("e-2", "Dentiste demain", startAt = "2026-11-09T09:00:00+01:00", endAt = "2026-11-09T10:00:00+01:00"),
                Event("e-3", "Plus tard ce soir", startAt = "2026-11-08T22:00:00+01:00", endAt = "2026-11-08T23:00:00+01:00"),
            ),
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = BilanJourneeViewModel(tasks, events) { now }

    @Test
    fun `loading shows the done count, the tasks left and the agenda that is over`() = runTest(testDispatcher) {
        val vm = viewModel()
        vm.load(emptyMap())
        advanceUntilIdle()

        val ui = vm.uiState.value
        assertEquals("1 faite sur 3", ui.count)
        assertEquals(listOf("Appeler le garage", "Trier les papiers"), ui.remaining.map { it.title })
        assertEquals(listOf(JourneeAgendaItem("09:00", "Point équipe")), ui.agenda)
    }

    @Test
    fun `Demain gives the task tomorrow as its due day, then records the choice`() = runTest(testDispatcher) {
        val vm = viewModel()
        vm.load(emptyMap())
        advanceUntilIdle()

        vm.choose("t-1", TaskChoice.TOMORROW, record)
        advanceUntilIdle()

        coVerify { tasks.setDueDate("t-1", "2026-11-09") }
        assertEquals(listOf("t-1" to "tomorrow"), recorded)
        assertEquals(TaskChoice.TOMORROW, vm.uiState.value.remaining.first { it.id == "t-1" }.choice)
    }

    @Test
    fun `Plus tard takes the due day away and keeps the task in the list`() = runTest(testDispatcher) {
        val vm = viewModel()
        vm.load(emptyMap())
        advanceUntilIdle()

        vm.choose("t-2", TaskChoice.LATER, record)
        advanceUntilIdle()

        coVerify { tasks.setDueDate("t-2", null) }
        assertEquals(listOf("t-2" to "later"), recorded)
        assertEquals(2, vm.uiState.value.remaining.size)
    }

    @Test
    fun `a network error gives the task back as it was, records nothing and says so`() = runTest(testDispatcher) {
        coEvery { tasks.setDueDate(any(), any()) } returns Result.failure(RuntimeException("network"))
        val vm = viewModel()
        vm.load(emptyMap())
        advanceUntilIdle()

        vm.choose("t-1", TaskChoice.TOMORROW, record)
        advanceUntilIdle()

        val task = vm.uiState.value.remaining.first { it.id == "t-1" }
        assertNull(task.choice)
        assertFalse(task.saving)
        assertNotNull(vm.uiState.value.message)
        assertEquals(emptyList<Pair<String, String?>>(), recorded)
    }

    @Test
    fun `resuming keeps the choices made, even for a task no longer due today`() = runTest(testDispatcher) {
        coEvery { tasks.getTodayTasks() } returns listOf(papers)
        coEvery { tasks.fetchTask("t-1") } returns Result.success(garage.copy(dueDate = "2026-11-09"))
        val vm = viewModel()

        vm.load(mapOf("t-1" to "tomorrow", "t-2" to "later"))
        advanceUntilIdle()

        val remaining = vm.uiState.value.remaining
        assertEquals(TaskChoice.LATER, remaining.first { it.id == "t-2" }.choice)
        assertEquals(TaskChoice.TOMORROW, remaining.first { it.id == "t-1" }.choice)
    }

    @Test
    fun `Oui demain puts every task not yet answered to tomorrow`() = runTest(testDispatcher) {
        val vm = viewModel()
        vm.load(mapOf("t-2" to "later"))
        advanceUntilIdle()

        vm.allTomorrow(record)
        advanceUntilIdle()

        assertEquals(listOf("t-1" to "tomorrow"), recorded)
        coVerify(exactly = 0) { tasks.setDueDate("t-2", "2026-11-09") }
    }

    @Test
    fun `a day that cannot be read is an error to retry`() = runTest(testDispatcher) {
        coEvery { tasks.getTodayTasks() } throws RuntimeException("network")
        val vm = viewModel()
        vm.load(emptyMap())
        advanceUntilIdle()

        assertEquals(true, vm.uiState.value.error)
    }
}

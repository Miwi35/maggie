package com.maggie.app.data.repository

import com.maggie.app.data.api.ApiRefusalException
import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.local.dao.TaskDao
import com.maggie.app.data.mercure.MercureEvent
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.model.DayRoutine
import com.maggie.app.data.model.HomeDay
import com.maggie.app.data.model.RoutineDayView
import com.maggie.app.data.model.RoutineMoment
import com.maggie.app.data.model.Task
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * The reads of the « Tâches » card (MAG-588): the task and routine resources, never the home card endpoint; a module
 * absent (404) is an empty card; the topics of tasks and routines wake it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TasksCardRepositoryTest {
    private val api = mockk<MaggieApiService>()
    private val mercure = mockk<MercureService>()
    private val auth = mockk<AuthRepository> { coEvery { getUserId() } returns "01HUSER" }
    private val repository = TasksCardRepository(TaskRepository(api, mockk<TaskDao>(relaxed = true)), RoutineRepository(api), mercure, auth)

    private val today = LocalDate.of(2026, 10, 23)
    private val noon = LocalTime.NOON

    private fun task(id: String, overdueDays: Int? = null) = Task(id = id, title = "Tâche $id", overdueDays = overdueDays)

    private val routines = RoutineDayView(
        date = "2026-10-23",
        moments = listOf(RoutineMoment("wake", "07:30", listOf(DayRoutine("r1", "Eau", "wake", null, "07:30")))),
    )

    private fun answers() {
        coEvery { api.getTodayTasks() } returns listOf(task("o1", overdueDays = 1), task("o2"))
        coEvery { api.getTasksCompletedBetween(any(), any(), any()) } returns listOf(task("d1"))
        coEvery { api.getOpenTasksDueOn(any(), any()) } returns listOf(task("t1"))
        coEvery { api.getRoutinesDay(any()) } returns routines
    }

    @Test
    fun `today is composed from the today view, the tasks done today and the routines of the day`() = runTest {
        answers()

        val card = repository.read(HomeDay.TODAY, today, noon).getOrThrow()

        assertEquals(listOf("o1", "o2", "d1"), card.tasks.map { it.id })
        assertEquals(1, card.done)
        assertEquals(1, card.overdue)
        assertEquals(listOf("r1"), card.routines.map { it.id })
        coVerify(exactly = 1) { api.getRoutinesDay("2026-10-23") }
    }

    @Test
    fun `the tasks done today are asked between midnight and the next one, in the user's zone, five at most`() = runTest {
        answers()

        repository.read(HomeDay.TODAY, today, noon).getOrThrow()

        coVerify(exactly = 1) {
            api.getTasksCompletedBetween("2026-10-23T00:00:00+02:00", "2026-10-24T00:00:00+02:00", 5)
        }
    }

    @Test
    fun `tomorrow asks the open tasks due that day and the routines of that day`() = runTest {
        answers()

        val card = repository.read(HomeDay.TOMORROW, today, noon).getOrThrow()

        assertFalse(card.editable)
        assertEquals(listOf("t1"), card.tasks.map { it.id })
        coVerify(exactly = 1) { api.getOpenTasksDueOn("2026-10-24", 100) }
        coVerify(exactly = 1) { api.getRoutinesDay("2026-10-24") }
        coVerify(exactly = 0) { api.getTodayTasks() }
        coVerify(exactly = 0) { api.getTasksCompletedBetween(any(), any(), any()) }
    }

    @Test
    fun `the card never reads the home card endpoint`() = runTest {
        answers()

        repository.read(HomeDay.TODAY, today, noon).getOrThrow()
        repository.read(HomeDay.TOMORROW, today, noon).getOrThrow()

        coVerify(exactly = 0) { api.getHomeCard(any(), any()) }
    }

    @Test
    fun `the three reads of today start together`() = runTest {
        val gate = CompletableDeferred<Unit>()
        coEvery { api.getTodayTasks() } coAnswers { gate.await(); emptyList() }
        coEvery { api.getTasksCompletedBetween(any(), any(), any()) } coAnswers { gate.await(); emptyList() }
        coEvery { api.getRoutinesDay(any()) } coAnswers { gate.await(); routines }

        val read = launch { repository.read(HomeDay.TODAY, today, noon) }
        runCurrent()

        coVerify(exactly = 1) { api.getTodayTasks() }
        coVerify(exactly = 1) { api.getTasksCompletedBetween(any(), any(), any()) }
        coVerify(exactly = 1) { api.getRoutinesDay(any()) }
        gate.complete(Unit)
        read.join()
    }

    @Test
    fun `a read answered 404 gives an empty card, which the Accueil does not draw`() = runTest {
        answers()
        coEvery { api.getTodayTasks() } throws ApiRefusalException(404, "Not found")

        val card = repository.read(HomeDay.TODAY, today, noon).getOrThrow()

        assertTrue(card.isEmpty)
        assertEquals("today", card.day)
    }

    @Test
    fun `routines answered 404 on tomorrow give an empty card too`() = runTest {
        answers()
        coEvery { api.getRoutinesDay(any()) } throws ApiRefusalException(404, "Not found")

        assertTrue(repository.read(HomeDay.TOMORROW, today, noon).getOrThrow().isEmpty)
    }

    @Test
    fun `any other failure is a failure`() = runTest {
        answers()
        coEvery { api.getTasksCompletedBetween(any(), any(), any()) } throws ApiRefusalException(500, "boom")
        assertTrue(repository.read(HomeDay.TODAY, today, noon).isFailure)

        coEvery { api.getTasksCompletedBetween(any(), any(), any()) } throws RuntimeException("offline")
        assertTrue(repository.read(HomeDay.TODAY, today, noon).isFailure)
    }

    @Test
    fun `a change on tasks, routines or routine checks wakes the card, bursts merged`() = runTest {
        every { mercure.subscribe("/users/01HUSER/api/tasks/{id}") } returns flowOf(MercureEvent(id = "1"), MercureEvent(id = "2"))
        every { mercure.subscribe("/users/01HUSER/api/routines/{id}") } returns flowOf()
        every { mercure.subscribe("/users/01HUSER/api/routine_checks/{id}") } returns flowOf()

        val changes = repository.changes().toList()

        assertEquals(1, changes.size)
        verify(exactly = 1) { mercure.subscribe("/users/01HUSER/api/tasks/{id}") }
        verify(exactly = 1) { mercure.subscribe("/users/01HUSER/api/routines/{id}") }
        verify(exactly = 1) { mercure.subscribe("/users/01HUSER/api/routine_checks/{id}") }
        verify(exactly = 0) { mercure.subscribe(match { it.contains("/home/") }) }
    }

    @Test
    fun `a routine ticked elsewhere wakes the card`() = runTest {
        val checks = MutableSharedFlow<MercureEvent>()
        every { mercure.subscribe("/users/01HUSER/api/tasks/{id}") } returns flowOf()
        every { mercure.subscribe("/users/01HUSER/api/routines/{id}") } returns flowOf()
        every { mercure.subscribe("/users/01HUSER/api/routine_checks/{id}") } returns checks
        var woken = 0
        val job = launch { repository.changes().collect { woken++ } }
        runCurrent()

        checks.emit(MercureEvent(id = "1"))
        advanceUntilIdle()

        assertEquals(1, woken)
        job.cancel()
    }

    @Test
    fun `without a signed-in user the card subscribes to nothing`() = runTest {
        coEvery { auth.getUserId() } returns null

        assertTrue(repository.changes().toList().isEmpty())
        verify(exactly = 0) { mercure.subscribe(any()) }
    }
}

package com.maggie.app.ui.components

import com.maggie.app.data.model.AgendaHomeState
import com.maggie.app.data.model.FinanceHomeState
import com.maggie.app.data.model.GroceryHomeState
import com.maggie.app.data.model.HomeTasks
import com.maggie.app.data.model.CuisineHomeState
import com.maggie.app.data.repository.AgendaCardRepository
import com.maggie.app.data.repository.FinanceCardRepository
import com.maggie.app.data.repository.GroceryCardRepository
import com.maggie.app.data.repository.CuisineCardRepository
import com.maggie.app.data.repository.TasksCardRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** The drawer's state of the day (MAG-620): read from the Accueil's repositories, again on a change and when asked. */
@OptIn(ExperimentalCoroutinesApi::class)
class DrawerSummaryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val tasks = mockk<TasksCardRepository>()
    private val agenda = mockk<AgendaCardRepository>()
    private val grocery = mockk<GroceryCardRepository>()
    private val meals = mockk<CuisineCardRepository>()
    private val finance = mockk<FinanceCardRepository>()
    private val taskChanges = MutableSharedFlow<Unit>()
    private val now = Instant.parse("2026-10-25T09:00:00Z")

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { tasks.changes() } returns taskChanges
        every { agenda.changes() } returns MutableSharedFlow()
        every { grocery.changes() } returns MutableSharedFlow()
        every { meals.changes() } returns MutableSharedFlow()
        every { finance.changes() } returns MutableSharedFlow()
        coEvery { tasks.read(any(), any(), any()) } returns Result.success(HomeTasks(done = 1, total = 4))
        coEvery { agenda.read(any(), any()) } returns Result.success(AgendaHomeState("2026-10-25"))
        coEvery { grocery.read(any()) } returns Result.success(GroceryHomeState(count = 3))
        coEvery { meals.read(any()) } returns Result.success(CuisineHomeState())
        coEvery { finance.read() } returns Result.success(FinanceHomeState(toQualifyCount = 2))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = DrawerSummaryViewModel(tasks, agenda, grocery, meals, finance) { now }

    @Test
    fun `the lines come from the answers of every module`() = runTest(dispatcher) {
        val viewModel = viewModel()
        assertEquals(emptyMap<String, DrawerEntryStatus>(), viewModel.summary.value.entries)

        advanceUntilIdle()

        val summary = viewModel.summary.value
        assertEquals(DrawerEntryStatus("1 sur 4 aujourd'hui", count = 3), summary["tasks"])
        assertEquals("3 articles", summary["grocery"]?.line)
        assertEquals(DrawerEntryStatus("2 à trier", count = 2), summary["finance_dashboard"])
        assertNull(summary["calendar"])
        assertNull(summary["cookbook"])
    }

    @Test
    fun `a module that fails says nothing while the others still speak`() = runTest(dispatcher) {
        coEvery { grocery.read(any()) } returns Result.failure(RuntimeException("503"))

        val viewModel = viewModel()
        advanceUntilIdle()

        assertNull(viewModel.summary.value["grocery"])
        assertEquals("1 sur 4 aujourd'hui", viewModel.summary.value["tasks"]?.line)
    }

    @Test
    fun `a change announced by a module reads it again`() = runTest(dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()
        coEvery { tasks.read(any(), any(), any()) } returns Result.success(HomeTasks(done = 2, total = 4))

        taskChanges.emit(Unit)
        advanceUntilIdle()

        assertEquals("2 sur 4 aujourd'hui", viewModel.summary.value["tasks"]?.line)
    }

    @Test
    fun `refresh reads again, and a failed read keeps the previous line`() = runTest(dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()
        coEvery { tasks.read(any(), any(), any()) } returns Result.failure(RuntimeException("offline"))

        viewModel.refresh()
        advanceUntilIdle()

        assertEquals("1 sur 4 aujourd'hui", viewModel.summary.value["tasks"]?.line)
    }
}

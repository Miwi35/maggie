package com.maggie.app.ui.screens.home

import com.maggie.app.data.model.HomeDay
import com.maggie.app.data.model.NutritionHomeMeal
import com.maggie.app.data.model.NutritionHomeSlot
import com.maggie.app.data.model.NutritionHomeState
import com.maggie.app.data.repository.NutritionCardRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/** The Cuisine card of the Accueil (MAG-564, MAG-591): read for the selected day, again when it moves, one test per state. */
@OptIn(ExperimentalCoroutinesApi::class)
class NutritionHomeViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repository = mockk<NutritionCardRepository>()
    private val changes = MutableSharedFlow<Unit>()

    private val today = LocalDate.of(2026, 10, 23)
    private val tomorrow = today.plusDays(1)
    private val clock = { Instant.parse("2026-10-23T10:00:00Z") }

    private fun answer(dinner: String?) = NutritionHomeState(
        slots = listOf(
            NutritionHomeSlot("lunch"),
            NutritionHomeSlot("dinner", listOfNotNull(dinner?.let { NutritionHomeMeal(id = "m1", title = it, guests = 2, recipes = listOf(it)) })),
        ),
    )

    private fun viewModel() = NutritionHomeViewModel(repository, clock)

    private fun dinner(viewModel: NutritionHomeViewModel) =
        viewModel.uiState.value.state?.slots?.get(1)?.meals?.singleOrNull()?.title

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { repository.changes() } returns changes
        coEvery { repository.read(today) } returns Result.success(answer("Pâtes"))
        coEvery { repository.read(tomorrow) } returns Result.success(answer("Soupe"))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `the card loads while it reads, then holds today's meals`() = runTest(dispatcher) {
        val viewModel = viewModel()

        assertTrue(viewModel.uiState.value.isLoading)
        assertNull(viewModel.uiState.value.state)

        advanceUntilIdle()

        val ui = viewModel.uiState.value
        assertFalse(ui.isLoading)
        assertFalse(ui.failed)
        assertEquals("Pâtes", dinner(viewModel))
        coVerify(exactly = 1) { repository.read(today) }
    }

    @Test
    fun `a failing read shows the failure, with no meals`() = runTest(dispatcher) {
        coEvery { repository.read(any()) } returns Result.failure(RuntimeException("503"))
        val viewModel = viewModel()

        advanceUntilIdle()

        val ui = viewModel.uiState.value
        assertTrue(ui.failed)
        assertFalse(ui.isLoading)
        assertNull(ui.state)
    }

    @Test
    fun `retrying after a failure reads again and clears it`() = runTest(dispatcher) {
        coEvery { repository.read(any()) } returns Result.failure(RuntimeException("503"))
        val viewModel = viewModel()
        advanceUntilIdle()

        coEvery { repository.read(today) } returns Result.success(answer("Pâtes"))
        viewModel.refresh()
        assertTrue(viewModel.uiState.value.isLoading)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.failed)
        assertNotNull(viewModel.uiState.value.state)
    }

    @Test
    fun `choosing Demain reads tomorrow, showing no meal of today meanwhile`() = runTest(dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.selectDay(HomeDay.TOMORROW)

        assertTrue(viewModel.uiState.value.isLoading)
        assertNull(viewModel.uiState.value.state)
        advanceUntilIdle()

        assertEquals("Soupe", dinner(viewModel))
        coVerify(exactly = 1) { repository.read(tomorrow) }
    }

    @Test
    fun `the day already shown is not read again`() = runTest(dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.selectDay(HomeDay.TODAY)
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.read(today) }
    }

    @Test
    fun `a change announced by Mercure reads the selected day again`() = runTest(dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.selectDay(HomeDay.TOMORROW)
        advanceUntilIdle()

        coEvery { repository.read(tomorrow) } returns Result.success(answer("Gratin"))
        changes.emit(Unit)
        advanceUntilIdle()

        assertEquals("Gratin", dinner(viewModel))
        coVerify(exactly = 2) { repository.read(tomorrow) }
    }

    @Test
    fun `a change does not show the loading state again`() = runTest(dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()

        changes.emit(Unit)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isLoading)
        assertEquals("Pâtes", dinner(viewModel))
    }

    @Test
    fun `a read that fails after a good one keeps the meals`() = runTest(dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()

        coEvery { repository.read(any()) } returns Result.failure(RuntimeException("offline"))
        changes.emit(Unit)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.failed)
        assertEquals("Pâtes", dinner(viewModel))
    }

    @Test
    fun `a late answer for the day left behind never replaces the day shown`() = runTest(dispatcher) {
        val viewModel = viewModel()
        // Today's first read is still in flight when the selector moves.
        viewModel.selectDay(HomeDay.TOMORROW)
        advanceUntilIdle()

        assertEquals("Soupe", dinner(viewModel))
    }

    @Test
    fun `status - loading, filled today, loading then empty on a day without meal, filled again, failed`() = runTest(dispatcher) {
        coEvery { repository.read(tomorrow) } returns Result.success(answer(null))
        val viewModel = viewModel()
        assertEquals(HomeCardStatus.LOADING, viewModel.uiState.value.status)

        advanceUntilIdle()
        assertEquals(HomeCardStatus.FILLED, viewModel.uiState.value.status)

        viewModel.selectDay(HomeDay.TOMORROW)
        assertEquals(HomeCardStatus.LOADING, viewModel.uiState.value.status)
        advanceUntilIdle()
        assertEquals(HomeCardStatus.EMPTY, viewModel.uiState.value.status)

        viewModel.selectDay(HomeDay.TODAY)
        advanceUntilIdle()
        assertEquals(HomeCardStatus.FILLED, viewModel.uiState.value.status)

        coEvery { repository.read(any()) } returns Result.failure(RuntimeException("offline"))
        viewModel.refresh()
        advanceUntilIdle()
        assertEquals(HomeCardStatus.ERROR, viewModel.uiState.value.status)
    }

    @Test
    fun `status - a meal pushed into an empty day brings the card back`() = runTest(dispatcher) {
        coEvery { repository.read(today) } returns Result.success(answer(null))
        val viewModel = viewModel()
        advanceUntilIdle()
        assertEquals(HomeCardStatus.EMPTY, viewModel.uiState.value.status)

        coEvery { repository.read(today) } returns Result.success(answer("Pâtes"))
        changes.emit(Unit)
        advanceUntilIdle()

        assertEquals(HomeCardStatus.FILLED, viewModel.uiState.value.status)
    }
}

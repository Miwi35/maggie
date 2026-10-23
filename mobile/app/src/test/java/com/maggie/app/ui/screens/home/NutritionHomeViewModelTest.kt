package com.maggie.app.ui.screens.home

import com.maggie.app.data.model.HomeDay
import com.maggie.app.data.repository.HomeCardRepository
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The Nutrition card of the Accueil (MAG-564): read for the selected day, again when it moves, one test per state. */
@OptIn(ExperimentalCoroutinesApi::class)
class NutritionHomeViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repository = mockk<HomeCardRepository>()
    private val changes = MutableSharedFlow<Unit>()

    private fun answer(day: String, dinner: String): JsonObject = Json.parseToJsonElement(
        """
        {"module":"nutrition","day":"$day","date":"2026-10-14","extra":"ignored","slots":[
          {"slot":"lunch","meals":[]},
          {"slot":"dinner","meals":[{"id":"m1","title":"$dinner","guests":2,
            "recipes":[{"id":"r1","name":"$dinner"}],"missing":[]}]}]}
        """.trimIndent(),
    ) as JsonObject

    private fun dinner(viewModel: NutritionHomeViewModel) =
        viewModel.uiState.value.state?.slots?.get(1)?.meals?.singleOrNull()?.title

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { repository.changes("nutrition") } returns changes
        coEvery { repository.state("nutrition", HomeDay.TODAY) } returns Result.success(answer("today", "Pâtes"))
        coEvery { repository.state("nutrition", HomeDay.TOMORROW) } returns Result.success(answer("tomorrow", "Soupe"))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `the card loads while it reads, then holds today's meals`() = runTest(dispatcher) {
        val viewModel = NutritionHomeViewModel(repository)

        assertTrue(viewModel.uiState.value.isLoading)
        assertNull(viewModel.uiState.value.state)

        advanceUntilIdle()

        val ui = viewModel.uiState.value
        assertFalse(ui.isLoading)
        assertFalse(ui.failed)
        assertEquals("today", ui.state?.day)
        assertEquals("Pâtes", dinner(viewModel))
    }

    @Test
    fun `a failing read shows the failure, with no meals`() = runTest(dispatcher) {
        coEvery { repository.state(any(), any()) } returns Result.failure(RuntimeException("503"))
        val viewModel = NutritionHomeViewModel(repository)

        advanceUntilIdle()

        val ui = viewModel.uiState.value
        assertTrue(ui.failed)
        assertFalse(ui.isLoading)
        assertNull(ui.state)
    }

    @Test
    fun `retrying after a failure reads again and clears it`() = runTest(dispatcher) {
        coEvery { repository.state(any(), any()) } returns Result.failure(RuntimeException("503"))
        val viewModel = NutritionHomeViewModel(repository)
        advanceUntilIdle()

        coEvery { repository.state("nutrition", HomeDay.TODAY) } returns Result.success(answer("today", "Pâtes"))
        viewModel.refresh()
        assertTrue(viewModel.uiState.value.isLoading)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.failed)
        assertNotNull(viewModel.uiState.value.state)
    }

    @Test
    fun `choosing Demain reads tomorrow, showing no meal of today meanwhile`() = runTest(dispatcher) {
        val viewModel = NutritionHomeViewModel(repository)
        advanceUntilIdle()

        viewModel.selectDay(HomeDay.TOMORROW)

        assertTrue(viewModel.uiState.value.isLoading)
        assertNull(viewModel.uiState.value.state)
        advanceUntilIdle()

        assertEquals("tomorrow", viewModel.uiState.value.state?.day)
        assertEquals("Soupe", dinner(viewModel))
    }

    @Test
    fun `the day already shown is not read again`() = runTest(dispatcher) {
        val viewModel = NutritionHomeViewModel(repository)
        advanceUntilIdle()

        viewModel.selectDay(HomeDay.TODAY)
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.state("nutrition", HomeDay.TODAY) }
    }

    @Test
    fun `a change announced by the API reads the selected day again`() = runTest(dispatcher) {
        val viewModel = NutritionHomeViewModel(repository)
        advanceUntilIdle()
        viewModel.selectDay(HomeDay.TOMORROW)
        advanceUntilIdle()

        coEvery { repository.state("nutrition", HomeDay.TOMORROW) } returns Result.success(answer("tomorrow", "Gratin"))
        changes.emit(Unit)
        advanceUntilIdle()

        assertEquals("Gratin", dinner(viewModel))
        coVerify(exactly = 2) { repository.state("nutrition", HomeDay.TOMORROW) }
    }

    @Test
    fun `a read that fails after a good one keeps the meals and flags the failure`() = runTest(dispatcher) {
        val viewModel = NutritionHomeViewModel(repository)
        advanceUntilIdle()

        coEvery { repository.state(any(), any()) } returns Result.failure(RuntimeException("offline"))
        changes.emit(Unit)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.failed)
        assertEquals("Pâtes", dinner(viewModel))
    }

    @Test
    fun `a late answer for the day left behind never replaces the day shown`() = runTest(dispatcher) {
        val viewModel = NutritionHomeViewModel(repository)
        // Today's first read is still in flight when the selector moves.
        viewModel.selectDay(HomeDay.TOMORROW)
        advanceUntilIdle()

        assertEquals("tomorrow", viewModel.uiState.value.state?.day)
    }
}

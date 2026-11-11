package com.maggie.app.ui.screens.home

import com.maggie.app.data.model.HealthHomeBalance
import com.maggie.app.data.model.HealthHomeState
import com.maggie.app.data.model.HomeDay
import com.maggie.app.data.repository.HealthCardRepository
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/** The Santé card of the Accueil (MAG-566): read for the selected day, again when it moves, one test per state. */
@OptIn(ExperimentalCoroutinesApi::class)
class HealthHomeViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repository = mockk<HealthCardRepository>()
    private val changes = MutableSharedFlow<Unit>()

    private val today = LocalDate.of(2026, 11, 11)
    private val tomorrow = today.plusDays(1)
    private val clock = { Instant.parse("2026-11-11T10:00:00Z") }

    private fun answer(kcal: Int, isToday: Boolean = true) =
        HealthHomeState(today = isToday, balance = if (kcal > 0) HealthHomeBalance(kcal, 1980) else null)

    private fun viewModel() = HealthHomeViewModel(repository, clock)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { repository.changes() } returns changes
        coEvery { repository.read(today, true, any<LocalTime>()) } returns Result.success(answer(1240))
        coEvery { repository.read(tomorrow, false, any<LocalTime>()) } returns Result.success(answer(1800, isToday = false))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `the card loads while it reads, then holds today`() = runTest(dispatcher) {
        val viewModel = viewModel()

        assertTrue(viewModel.uiState.value.isLoading)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isLoading)
        assertEquals(1240, viewModel.uiState.value.state?.balance?.kcal)
        coVerify(exactly = 1) { repository.read(today, true, any<LocalTime>()) }
    }

    @Test
    fun `a failing read shows the failure`() = runTest(dispatcher) {
        coEvery { repository.read(any(), any(), any<LocalTime>()) } returns Result.failure(RuntimeException("503"))
        val viewModel = viewModel()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.failed)
        assertNull(viewModel.uiState.value.state)
        assertEquals(HomeCardStatus.ERROR, viewModel.uiState.value.status)
    }

    @Test
    fun `retrying after a failure reads again and clears it`() = runTest(dispatcher) {
        coEvery { repository.read(any(), any(), any<LocalTime>()) } returns Result.failure(RuntimeException("503"))
        val viewModel = viewModel()
        advanceUntilIdle()

        coEvery { repository.read(today, true, any<LocalTime>()) } returns Result.success(answer(1240))
        viewModel.refresh()
        assertTrue(viewModel.uiState.value.isLoading)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.failed)
        assertEquals(HomeCardStatus.FILLED, viewModel.uiState.value.status)
    }

    @Test
    fun `choosing Demain reads tomorrow, showing nothing of today meanwhile`() = runTest(dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.selectDay(HomeDay.TOMORROW)
        assertTrue(viewModel.uiState.value.isLoading)
        assertNull(viewModel.uiState.value.state)
        advanceUntilIdle()

        assertEquals(1800, viewModel.uiState.value.state?.balance?.kcal)
        coVerify(exactly = 1) { repository.read(tomorrow, false, any<LocalTime>()) }
    }

    @Test
    fun `the day already shown is not read again`() = runTest(dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.selectDay(HomeDay.TODAY)
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.read(today, true, any<LocalTime>()) }
    }

    @Test
    fun `a change announced by Mercure reads the day again without the loading state`() = runTest(dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()

        coEvery { repository.read(today, true, any<LocalTime>()) } returns Result.success(answer(1500))
        changes.emit(Unit)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isLoading)
        assertEquals(1500, viewModel.uiState.value.state?.balance?.kcal)
    }

    @Test
    fun `a read that fails after a good one keeps what is shown`() = runTest(dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()

        coEvery { repository.read(any(), any(), any<LocalTime>()) } returns Result.failure(RuntimeException("offline"))
        changes.emit(Unit)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.failed)
        assertEquals(1240, viewModel.uiState.value.state?.balance?.kcal)
    }

    @Test
    fun `status - empty on a day with nothing, and back when something arrives`() = runTest(dispatcher) {
        coEvery { repository.read(today, true, any<LocalTime>()) } returns Result.success(answer(0))
        val viewModel = viewModel()
        assertEquals(HomeCardStatus.LOADING, viewModel.uiState.value.status)
        advanceUntilIdle()
        assertEquals(HomeCardStatus.EMPTY, viewModel.uiState.value.status)

        coEvery { repository.read(today, true, any<LocalTime>()) } returns Result.success(answer(900))
        changes.emit(Unit)
        advanceUntilIdle()

        assertEquals(HomeCardStatus.FILLED, viewModel.uiState.value.status)
    }
}

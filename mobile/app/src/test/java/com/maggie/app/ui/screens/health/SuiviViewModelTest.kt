package com.maggie.app.ui.screens.health

import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureEvent
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.mercure.MercureTopics
import com.maggie.app.data.model.BodyMeasurement
import com.maggie.app.data.repository.BodyMeasurementRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/** « Santé › Suivi » (MAG-706): one test per transition — loading, period, column, Mercure, « Écarter ». */
@OptIn(ExperimentalCoroutinesApi::class)
class SuiviViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val repository = mockk<BodyMeasurementRepository>()
    private val authRepository = mockk<AuthRepository>()
    private val mercureService = mockk<MercureService>()
    private val pushes = MutableSharedFlow<MercureEvent>()
    private val clock = Clock.fixed(Instant.parse("2026-10-21T08:15:30Z"), ZoneId.of("Europe/Paris"))

    private val today = BodyMeasurement("today", "2026-10-21T07:12:00+02:00", weightKg = 72.4, bodyFatPercent = 21.3)
    private val monday = BodyMeasurement("monday", "2026-10-19T07:05:00+02:00", weightKg = 72.8)

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        coEvery { repository.getBodyMeasurements(any()) } returns Result.success(listOf(today, monday))
        coEvery { authRepository.getUserId() } returns "user-1"
        every { mercureService.subscribe(any()) } returns pushes
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = SuiviViewModel(repository, mercureService, authRepository, clock)

    @Test
    fun `loading, then the weighings of the last year, 30 days shown`() = runTest {
        val viewModel = viewModel()
        assertTrue(viewModel.uiState.value.isLoading)
        assertFalse(viewModel.uiState.value.hasLoaded)

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertTrue(state.hasLoaded)
        assertEquals(listOf("today", "monday"), state.measurements.map { it.id })
        assertEquals(SuiviPeriod.MONTH, state.period)
        assertNull(state.selectedIndex)
        coVerify { repository.getBodyMeasurements("2025-10-26T00:00:00+02:00") }
    }

    @Test
    fun `a failed load says so in French and keeps the card out`() = runTest {
        coEvery { repository.getBodyMeasurements(any()) } returns Result.failure(RuntimeException("offline"))
        val viewModel = viewModel()
        advanceUntilIdle()

        assertEquals(SuiviViewModel.MESSAGE_LOAD, viewModel.uiState.value.error)
        assertFalse(viewModel.uiState.value.hasLoaded)
    }

    @Test
    fun `a new period shows its last column and asks nothing of the API`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.selectColumn(3)

        viewModel.selectPeriod(SuiviPeriod.YEAR)
        advanceUntilIdle()

        assertEquals(SuiviPeriod.YEAR, viewModel.uiState.value.period)
        assertNull(viewModel.uiState.value.selectedIndex)
        coVerify(exactly = 1) { repository.getBodyMeasurements(any()) }
    }

    @Test
    fun `a tap chooses a column`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.selectColumn(27)

        assertEquals(27, viewModel.uiState.value.selectedIndex)
    }

    @Test
    fun `a weighing pushed on the body-measurements topic appears without a reload`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()
        val fromMaggie = BodyMeasurement("maggie", "2026-10-21T09:00:00+02:00", weightKg = 71.8)
        coEvery { repository.getBodyMeasurements(any()) } returns Result.success(listOf(fromMaggie, today, monday))

        pushes.emit(MercureEvent(data = "{}"))
        advanceUntilIdle()

        verify { mercureService.subscribe(MercureTopics.userScoped("user-1", MercureTopics.BODY_MEASUREMENTS)) }
        assertEquals(listOf("maggie", "today", "monday"), viewModel.uiState.value.measurements.map { it.id })
    }

    @Test
    fun `Écarter sets the weighing aside at once, patches it and says so`() = runTest {
        coEvery { repository.setExcluded("today", true) } returns Result.success(today.copy(excluded = true))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.exclude("today")
        assertTrue(viewModel.uiState.value.measurements.first { it.id == "today" }.excluded)
        coEvery { repository.getBodyMeasurements(any()) } returns Result.success(listOf(today.copy(excluded = true), monday))
        advanceUntilIdle()

        coVerify { repository.setExcluded("today", true) }
        assertEquals(SuiviViewModel.MESSAGE_EXCLUDED, viewModel.uiState.value.message)
        assertTrue(viewModel.uiState.value.measurements.first { it.id == "today" }.excluded)
    }

    @Test
    fun `a refused Écarter puts the weighing back and says why`() = runTest {
        coEvery { repository.setExcluded("today", true) } returns Result.failure(RuntimeException("422"))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.exclude("today")
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.measurements.first { it.id == "today" }.excluded)
        assertEquals(SuiviViewModel.MESSAGE_EXCLUDE, viewModel.uiState.value.error)
    }
}

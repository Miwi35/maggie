package com.maggie.app.ui.screens.health

import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureEvent
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.mercure.MercureTopics
import com.maggie.app.data.model.BodyMeasurement
import com.maggie.app.data.model.DailyBalance
import com.maggie.app.data.model.DailyEnergy
import com.maggie.app.data.repository.BodyMeasurementRepository
import com.maggie.app.data.repository.DailyEnergyRepository
import com.maggie.app.data.repository.NutritionRepository
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
    private val energyRepository = mockk<DailyEnergyRepository>()
    private val nutritionRepository = mockk<NutritionRepository>()
    private val foodPushes = MutableSharedFlow<MercureEvent>()
    private val bilan = DailyBalance("2026-10-21", eatenKcal = 515, plannedKcal = 0, expendedKcal = 2195, balanceKcal = -1680)
    private val authRepository = mockk<AuthRepository>()
    private val mercureService = mockk<MercureService>()
    private val pushes = MutableSharedFlow<MercureEvent>()
    private val clock = Clock.fixed(Instant.parse("2026-10-21T08:15:30Z"), ZoneId.of("Europe/Paris"))

    private val today = BodyMeasurement("today", "2026-10-21T07:12:00+02:00", weightKg = 72.4, bodyFatPercent = 21.3)
    private val monday = BodyMeasurement("monday", "2026-10-19T07:05:00+02:00", weightKg = 72.8)

    private val energyDay = DailyEnergy("2026-10-21", steps = 8000, activeKcal = 400, bmrKcal = 1648, workoutKcal = 0, stepsKcal = 14, expendedKcal = 1662)

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        coEvery { repository.getBodyMeasurements(any()) } returns Result.success(listOf(today, monday))
        coEvery { energyRepository.getDailyEnergy(any(), any()) } returns Result.success(listOf(energyDay))
        coEvery { authRepository.getUserId() } returns "user-1"
        every { mercureService.subscribe(any()) } returns pushes
        every { mercureService.subscribe(MercureTopics.userScoped("user-1", MercureTopics.FOOD_INTAKES)) } returns foodPushes
        coEvery { nutritionRepository.getDailyBalance(any()) } returns Result.success(bilan)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = SuiviViewModel(repository, energyRepository, nutritionRepository, mercureService, authRepository, clock)

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

    @Test
    fun `the energy is read once for the last 365 days, today included`() = runTest {
        val viewModel = viewModel()
        assertFalse(viewModel.uiState.value.energyLoaded)

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.energyLoaded)
        assertFalse(state.energyFailed)
        assertEquals(listOf(energyDay), state.energy)
        coVerify(exactly = 1) { energyRepository.getDailyEnergy("2025-10-22", "2026-10-21") }
    }

    @Test
    fun `an energy that cannot be read is flagged and leaves the weighings alone`() = runTest {
        coEvery { energyRepository.getDailyEnergy(any(), any()) } returns Result.failure(RuntimeException("offline"))
        val viewModel = viewModel()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.energyFailed)
        assertFalse(viewModel.uiState.value.energyLoaded)
        assertTrue(viewModel.uiState.value.hasLoaded)
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `a retry that succeeds clears the energy failure`() = runTest {
        coEvery { energyRepository.getDailyEnergy(any(), any()) } returns Result.failure(RuntimeException("offline"))
        val viewModel = viewModel()
        advanceUntilIdle()
        coEvery { energyRepository.getDailyEnergy(any(), any()) } returns Result.success(listOf(energyDay))

        viewModel.refresh()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.energyLoaded)
        assertFalse(viewModel.uiState.value.energyFailed)
    }

    @Test
    fun `a period change asks nothing of the energy API and resets the day`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.selectDay(java.time.LocalDate.parse("2026-10-19"))

        viewModel.selectPeriod(SuiviPeriod.QUARTER)
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.selectedDate)
        coVerify(exactly = 1) { energyRepository.getDailyEnergy(any(), any()) }
    }

    @Test
    fun `a day tapped on the energy cards chooses that day and its Poids column`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.selectDay(java.time.LocalDate.parse("2026-10-19"))

        assertEquals(java.time.LocalDate.parse("2026-10-19"), viewModel.uiState.value.selectedDate)
        assertEquals(27, viewModel.uiState.value.selectedIndex)
    }

    @Test
    fun `a Poids column of one day chooses that day for the energy cards`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.selectColumn(27)

        assertEquals(java.time.LocalDate.parse("2026-10-19"), viewModel.uiState.value.selectedDate)
    }

    @Test
    fun `a day tapped over a 90 day period has no Poids column to follow it`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.selectPeriod(SuiviPeriod.QUARTER)

        viewModel.selectDay(java.time.LocalDate.parse("2026-10-19"))

        assertEquals(java.time.LocalDate.parse("2026-10-19"), viewModel.uiState.value.selectedDate)
        assertNull(viewModel.uiState.value.selectedIndex)
    }

    @Test
    fun `Suivi reads today's bilan for the card it opens on (MAG-748)`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        coVerify(exactly = 1) { nutritionRepository.getDailyBalance("2026-10-21") }
        assertEquals(bilan, viewModel.uiState.value.balance)
    }

    @Test
    fun `a bilan that cannot be read leaves the card out and the weighings alone`() = runTest {
        coEvery { nutritionRepository.getDailyBalance(any()) } returns Result.failure(RuntimeException("offline"))
        val viewModel = viewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNull(state.balance)
        assertNull(state.error)
        assertTrue(state.hasLoaded)
        assertEquals(2, state.measurements.size)
    }

    @Test
    fun `a food intake pushed moves the bilan without reading the weighings again`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()
        val moved = bilan.copy(eatenKcal = 595, balanceKcal = -1600)
        coEvery { nutritionRepository.getDailyBalance(any()) } returns Result.success(moved)

        foodPushes.emit(MercureEvent(data = "{}"))
        advanceUntilIdle()

        assertEquals(moved, viewModel.uiState.value.balance)
        coVerify(exactly = 1) { repository.getBodyMeasurements(any()) }
    }
}

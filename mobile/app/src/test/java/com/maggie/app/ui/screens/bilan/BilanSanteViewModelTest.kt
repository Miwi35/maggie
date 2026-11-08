package com.maggie.app.ui.screens.bilan

import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureEvent
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.model.HealthDay
import com.maggie.app.data.repository.HealthDayRepository
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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** The Santé step of the Bilan (MAG-754): the day read, a scale written then recorded, a refusal, a value off the scale. */
@OptIn(ExperimentalCoroutinesApi::class)
class BilanSanteViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val repository = mockk<HealthDayRepository>()
    private val authRepository = mockk<AuthRepository>()
    private val mercureService = mockk<MercureService>()
    private val pushes = MutableSharedFlow<MercureEvent>()
    private val day = LocalDate.of(2026, 11, 8)
    private val empty = HealthDay(date = "2026-11-08", steps = 8420)

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        coEvery { repository.getDay("2026-11-08") } returns Result.success(empty)
        coEvery { authRepository.getUserId() } returns "user-1"
        every { mercureService.subscribe(any()) } returns pushes
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = BilanSanteViewModel(repository, mercureService, authRepository)

    @Test
    fun `opening reads the day's entry`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.open(day)
        advanceUntilIdle()

        assertEquals(8420, vm.uiState.value.entry?.steps)
    }

    @Test
    fun `a mood is written to the journal then recorded in the answers`() = runTest(dispatcher) {
        coEvery { repository.setMood("2026-11-08", 7) } returns Result.success(empty.copy(mood = 7))
        val recorded = mutableListOf<Int>()
        val vm = viewModel()
        vm.open(day)
        advanceUntilIdle()

        vm.pickMood(7) { recorded += it }
        advanceUntilIdle()

        assertEquals(7, vm.uiState.value.entry?.mood)
        assertEquals(listOf(7), recorded)
    }

    @Test
    fun `a fatigue is written to the journal then recorded in the answers`() = runTest(dispatcher) {
        coEvery { repository.setFatigue("2026-11-08", 4) } returns Result.success(empty.copy(fatigue = 4))
        val recorded = mutableListOf<Int>()
        val vm = viewModel()
        vm.open(day)
        advanceUntilIdle()

        vm.pickFatigue(4) { recorded += it }
        advanceUntilIdle()

        assertEquals(4, vm.uiState.value.entry?.fatigue)
        assertEquals(listOf(4), recorded)
    }

    @Test
    fun `a refused write says so and records nothing`() = runTest(dispatcher) {
        coEvery { repository.setMood("2026-11-08", 7) } returns Result.failure(RuntimeException("boom"))
        val recorded = mutableListOf<Int>()
        val vm = viewModel()
        vm.open(day)
        advanceUntilIdle()

        vm.pickMood(7) { recorded += it }
        advanceUntilIdle()

        assertEquals(BilanSanteViewModel.MESSAGE_WRITE, vm.uiState.value.error)
        assertNull(vm.uiState.value.entry?.mood)
        assertEquals(emptyList<Int>(), recorded)
    }

    @Test
    fun `a value off one to ten is never sent`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.open(day)
        advanceUntilIdle()

        vm.pickMood(0) {}
        vm.pickMood(11) {}
        vm.pickFatigue(-3) {}
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.setMood(any(), any()) }
        coVerify(exactly = 0) { repository.setFatigue(any(), any()) }
    }

    @Test
    fun `Maggie writing the entry by voice reaches the step through Mercure`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.open(day)
        advanceUntilIdle()
        coEvery { repository.getDay("2026-11-08") } returns Result.success(empty.copy(mood = 7, fatigue = 4, notedBy = "maggie"))

        pushes.emit(MercureEvent(data = "{}"))
        advanceUntilIdle()

        assertEquals(7, vm.uiState.value.entry?.mood)
        assertEquals(4, vm.uiState.value.entry?.fatigue)
    }
}

class BilanSanteFiguresTest {
    private val paris = ZoneId.of("Europe/Paris")

    @Test
    fun `figures show what exists and a dash for the rest`() {
        val figures = santeFigures(HealthDay(date = "2026-11-08", steps = 8420, sportMinutes = 45, weightKg = 79.4, weighedAt = "2026-11-08T07:12:00+01:00", balanceKcal = -312, eatenKcal = 1806, expendedKcal = 2118), paris)

        assertEquals(listOf("−312 kcal", "8 420", "45 min", "79,4 kg · 07:12"), figures.map { it.value })
        assertEquals("1 806 · 2 118", figures.first().detail)
    }

    @Test
    fun `without steps nor weighing the figures are dashes`() {
        val figures = santeFigures(HealthDay(date = "2026-11-08"), paris)

        assertEquals(listOf("—", "—", "—", "—"), figures.map { it.value })
    }

    @Test
    fun `Maggie's line names the figures without a verdict and asks the two questions`() {
        val line = santeSay(HealthDay(date = "2026-11-08", steps = 8420, sportMinutes = 45, balanceKcal = -312))

        assertEquals("Séance faite, 8 420 pas et un déficit de 312 kcal. Et votre moral, sur 10 ? Votre fatigue ?", line)
    }

    @Test
    fun `with no source the line still asks the questions`() {
        assertEquals("Et votre moral, sur 10 ? Votre fatigue ?", santeSay(HealthDay(date = "2026-11-08")))
    }
}

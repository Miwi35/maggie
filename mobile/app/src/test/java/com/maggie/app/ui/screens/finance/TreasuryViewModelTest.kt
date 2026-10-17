package com.maggie.app.ui.screens.finance

import com.maggie.app.data.api.ApiRefusalException
import com.maggie.app.data.api.TreasurySettingsRequest
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureEvent
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.model.BalanceBeforeSalary
import com.maggie.app.data.model.TreasuryOutlook
import com.maggie.app.data.model.TreasuryOverdraft
import com.maggie.app.data.model.TreasurySalary
import com.maggie.app.data.model.TreasurySettings
import com.maggie.app.data.repository.TreasuryRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
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

@OptIn(ExperimentalCoroutinesApi::class)
class TreasuryViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var treasuryRepository: TreasuryRepository
    private lateinit var mercureService: MercureService
    private lateinit var authRepository: AuthRepository
    private val mercureEvents = MutableSharedFlow<MercureEvent>(extraBufferCapacity = 8)

    private val configured = TreasuryOutlook(
        today = "2026-10-13",
        salary = TreasurySalary(isConfigured = true, expectedOn = "2026-10-10", isLate = false),
        balanceCents = 120000,
        overdraft = TreasuryOverdraft(limitCents = 50000),
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        treasuryRepository = mockk()
        mercureService = mockk()
        authRepository = mockk()
        coEvery { treasuryRepository.getOutlook() } returns Result.success(configured)
        coEvery { authRepository.getUserId() } returns "user-1"
        every { mercureService.subscribe(any()) } returns mercureEvents
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = TreasuryViewModel(treasuryRepository, mercureService, authRepository)

    @Test
    fun `it loads the outlook`() = runTest {
        val viewModel = viewModel()
        assertTrue(viewModel.uiState.value.outlook == null)

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(configured, state.outlook)
        assertFalse(state.isLoading)
        assertNull(state.error)
    }

    @Test
    fun `a salary day not yet set leaves the outlook unconfigured`() = runTest {
        coEvery { treasuryRepository.getOutlook() } returns Result.success(TreasuryOutlook())

        val viewModel = viewModel()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.outlook!!.salary.isConfigured)
    }

    @Test
    fun `a late salary keeps its delay`() = runTest {
        val late = configured.copy(salary = configured.salary.copy(isLate = true, delayDays = 3))
        coEvery { treasuryRepository.getOutlook() } returns Result.success(late)

        val viewModel = viewModel()
        advanceUntilIdle()

        val salary = viewModel.uiState.value.outlook!!.salary
        assertTrue(salary.isLate)
        assertEquals(3, salary.delayDays)
    }

    @Test
    fun `a received salary is kept as received`() = runTest {
        val received = configured.copy(salary = configured.salary.copy(isReceived = true))
        coEvery { treasuryRepository.getOutlook() } returns Result.success(received)

        val viewModel = viewModel()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.outlook!!.salary.isReceived)
    }

    @Test
    fun `a forecast overdraft is exposed with the balance before the salary`() = runTest {
        val forecast = configured.copy(
            overdraft = TreasuryOverdraft(isForecast = true, onDate = "2026-10-20", worstBalanceCents = -30000, limitCents = 0),
            balanceBeforeSalary = BalanceBeforeSalary(onDate = "2026-10-24", balanceCents = -30000),
        )
        coEvery { treasuryRepository.getOutlook() } returns Result.success(forecast)

        val viewModel = viewModel()
        advanceUntilIdle()

        val outlook = viewModel.uiState.value.outlook!!
        assertTrue(outlook.overdraft.isForecast)
        assertEquals("2026-10-20", outlook.overdraft.onDate)
        assertEquals(-30000, outlook.overdraft.worstBalanceCents)
    }

    @Test
    fun `a failed load sets an error and no outlook`() = runTest {
        coEvery { treasuryRepository.getOutlook() } returns Result.failure(RuntimeException("boom"))

        val viewModel = viewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("boom", state.error)
        assertFalse(state.errorIsMessage)
        assertNull(state.outlook)
        assertFalse(state.isLoading)
    }

    @Test
    fun `picking a horizon reads the outlook again for that many months, the default asks for none`() = runTest {
        val year = configured.copy(balanceCents = 777)
        coEvery { treasuryRepository.getOutlook(12) } returns Result.success(year)

        val viewModel = viewModel()
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.months)

        viewModel.selectHorizon(12)
        advanceUntilIdle()

        assertEquals(12, viewModel.uiState.value.months)
        assertEquals(year, viewModel.uiState.value.outlook)
        coVerify(exactly = 1) { treasuryRepository.getOutlook(12) }

        viewModel.selectHorizon(null)
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.months)
        assertEquals(configured, viewModel.uiState.value.outlook)
    }

    @Test
    fun `a slow answer for a horizon left behind never replaces the one asked for last`() = runTest {
        val year = configured.copy(balanceCents = 1200)
        val quarter = configured.copy(balanceCents = 300)
        coEvery { treasuryRepository.getOutlook(12) } coAnswers {
            delay(1_000)
            Result.success(year)
        }
        coEvery { treasuryRepository.getOutlook(3) } returns Result.success(quarter)

        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.selectHorizon(12)
        runCurrent()
        viewModel.selectHorizon(3)
        advanceUntilIdle()

        assertEquals(3, viewModel.uiState.value.months)
        assertNull(viewModel.uiState.value.error)
        assertEquals(quarter, viewModel.uiState.value.outlook)
        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test
    fun `picking the horizon already shown reads nothing`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.selectHorizon(null)
        advanceUntilIdle()

        coVerify(exactly = 1) { treasuryRepository.getOutlook(null) }
    }

    @Test
    fun `a failed read of a new horizon keeps the horizon and the outlook already shown`() = runTest {
        coEvery { treasuryRepository.getOutlook(6) } returns Result.failure(RuntimeException("boom"))

        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.selectHorizon(6)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(6, state.months)
        assertEquals("boom", state.error)
        assertEquals(configured, state.outlook)
    }

    @Test
    fun `saving the overdraft limit sends euros as cents, then reloads`() = runTest {
        coEvery { treasuryRepository.updateSettings(any()) } returns Result.success(TreasurySettings(overdraftLimitCents = 25050))

        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.saveOverdraftLimit("250,5")
        advanceUntilIdle()

        coVerify { treasuryRepository.updateSettings(TreasurySettingsRequest(overdraftLimitCents = 25050)) }
        coVerify(exactly = 2) { treasuryRepository.getOutlook() }
        val state = viewModel.uiState.value
        assertEquals("Découvert autorisé enregistré", state.savedMessage)
        assertNull(state.error)
        assertFalse(state.isSaving)
    }

    @Test
    fun `a negative limit is refused before any request`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.saveOverdraftLimit("-10")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(NEGATIVE_LIMIT_MESSAGE, state.error)
        assertTrue(state.errorIsMessage)
        coVerify(exactly = 0) { treasuryRepository.updateSettings(any()) }
    }

    @Test
    fun `a limit that is not a number is refused before any request`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.saveOverdraftLimit("beaucoup")
        advanceUntilIdle()

        assertEquals(INVALID_LIMIT_MESSAGE, viewModel.uiState.value.error)
        coVerify(exactly = 0) { treasuryRepository.updateSettings(any()) }

        viewModel.saveOverdraftLimit("NaN")
        advanceUntilIdle()

        assertEquals(INVALID_LIMIT_MESSAGE, viewModel.uiState.value.error)
        coVerify(exactly = 0) { treasuryRepository.updateSettings(any()) }
    }

    @Test
    fun `a server refusal is shown as its own message`() = runTest {
        coEvery { treasuryRepository.updateSettings(any()) } returns
            Result.failure(ApiRefusalException(400, "La limite est trop haute."))

        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.saveOverdraftLimit("999999")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("La limite est trop haute.", state.error)
        assertTrue(state.errorIsMessage)
        assertNull(state.savedMessage)
        assertFalse(state.isSaving)
    }

    @Test
    fun `saving the salary day sends it, then reloads`() = runTest {
        coEvery { treasuryRepository.updateSettings(any()) } returns Result.success(TreasurySettings(salaryExpectedDay = 28))

        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.saveSalaryDay("28")
        advanceUntilIdle()

        coVerify { treasuryRepository.updateSettings(TreasurySettingsRequest(salaryExpectedDay = 28)) }
        assertEquals("Jour du salaire enregistré", viewModel.uiState.value.savedMessage)
    }

    @Test
    fun `a salary day outside 1 to 31 is refused before any request`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        listOf("0", "32", "", "x").forEach { day ->
            viewModel.saveSalaryDay(day)
            advanceUntilIdle()

            assertEquals(INVALID_DAY_MESSAGE, viewModel.uiState.value.error)
            viewModel.clearError()
        }

        coVerify(exactly = 0) { treasuryRepository.updateSettings(any()) }
    }

    @Test
    fun `a transaction change reloads the outlook`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        mercureEvents.tryEmit(MercureEvent(data = "{}"))
        advanceUntilIdle()

        coVerify(atLeast = 2) { treasuryRepository.getOutlook() }
        assertNotNull(viewModel.uiState.value.outlook)
    }
}

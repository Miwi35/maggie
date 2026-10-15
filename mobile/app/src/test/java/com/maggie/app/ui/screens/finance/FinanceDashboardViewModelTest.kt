package com.maggie.app.ui.screens.finance

import com.maggie.app.data.model.DashboardBalance
import com.maggie.app.data.model.FinanceDashboard
import com.maggie.app.data.model.DailyScore
import com.maggie.app.data.model.MonthlyFlow
import com.maggie.app.data.model.BalanceBeforeSalary
import com.maggie.app.data.model.ReferenceSalary
import com.maggie.app.data.model.SavingCapacity
import com.maggie.app.data.model.TreasuryOutlook
import com.maggie.app.data.model.TreasuryOverdraft
import com.maggie.app.data.repository.FinanceDashboardRepository
import com.maggie.app.data.repository.TreasuryRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class FinanceDashboardViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var dashboardRepository: FinanceDashboardRepository
    private lateinit var treasuryRepository: TreasuryRepository
    private lateinit var viewModel: FinanceDashboardViewModel

    private val today = LocalDate.of(2026, 9, 17)

    private val dashboard = FinanceDashboard(
        year = 2026,
        month = 9,
        balance = DashboardBalance(totalCents = 950000, cushionCents = 750000, availableCents = 200000),
        monthlyFlows = listOf(
            MonthlyFlow(month = "2026-09", incomeCents = 350000, expenseCents = 25000, netCents = 325000),
        ),
        savingCapacity = SavingCapacity(netCapacityCents = 290000, isIncomeKnown = true),
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        dashboardRepository = mockk()
        treasuryRepository = mockk()
        coEvery { treasuryRepository.getOutlook() } returns Result.success(TreasuryOutlook())
        coEvery { dashboardRepository.getDashboard(any(), any()) } returns Result.success(dashboard)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun buildViewModel() = FinanceDashboardViewModel(dashboardRepository, treasuryRepository, today)

    @Test
    fun `it opens on the current month`() = runTest {
        viewModel = buildViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(2026, state.year)
        assertEquals(9, state.month)
        assertEquals(950000, state.dashboard?.balance?.totalCents)
        assertFalse(state.isLoading)
        assertNull(state.error)

        coVerify { dashboardRepository.getDashboard(2026, 9) }
    }

    @Test
    fun `shiftPeriod walks through the months`() = runTest {
        viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.shiftPeriod(-1)
        advanceUntilIdle()

        assertEquals(8, viewModel.uiState.value.month)
        coVerify { dashboardRepository.getDashboard(2026, 8) }
    }

    @Test
    fun `load failure sets error`() = runTest {
        coEvery { dashboardRepository.getDashboard(any(), any()) } returns
            Result.failure(RuntimeException("boom"))

        viewModel = buildViewModel()
        advanceUntilIdle()

        assertEquals("boom", viewModel.uiState.value.error)
        assertNull(viewModel.uiState.value.dashboard)
    }

    private val overdraftOutlook = TreasuryOutlook(
        overdraft = TreasuryOverdraft(isForecast = true, onDate = "2026-10-20", worstBalanceCents = -30000),
        balanceBeforeSalary = BalanceBeforeSalary(onDate = "2026-10-24", balanceCents = -30000),
    )

    @Test
    fun `a forecast overdraft gives the treasury banner`() = runTest {
        coEvery { treasuryRepository.getOutlook() } returns Result.success(overdraftOutlook)

        viewModel = buildViewModel()
        advanceUntilIdle()

        val banner = viewModel.uiState.value.treasuryBanner
        assertEquals("2026-10-20", banner?.overdraftOn)
        assertEquals(-30000, banner?.balanceBeforeSalaryCents)
        assertEquals("2026-10-24", banner?.balanceBeforeSalaryOn)
    }

    @Test
    fun `no forecast overdraft gives no banner`() = runTest {
        viewModel = buildViewModel()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.treasuryBanner)
    }

    @Test
    fun `an outlook that fails to load leaves the dashboard whole, without banner`() = runTest {
        coEvery { treasuryRepository.getOutlook() } returns Result.failure(RuntimeException("boom"))

        viewModel = buildViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNull(state.treasuryBanner)
        assertNull(state.error)
        assertEquals(950000, state.dashboard?.balance?.totalCents)
    }

    @Test
    fun `refreshTreasury drops the banner once the overdraft is covered`() = runTest {
        coEvery { treasuryRepository.getOutlook() } returns Result.success(overdraftOutlook)
        viewModel = buildViewModel()
        advanceUntilIdle()
        assertEquals("2026-10-20", viewModel.uiState.value.treasuryBanner?.overdraftOn)

        coEvery { treasuryRepository.getOutlook() } returns Result.success(TreasuryOutlook())
        viewModel.refreshTreasury()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.treasuryBanner)
    }

    private fun dashboardWith(reference: ReferenceSalary) =
        dashboard.copy(score = DailyScore(referenceSalary = reference))

    @Test
    fun `a declared reference salary reaches the state with its source`() = runTest {
        coEvery { dashboardRepository.getDashboard(any(), any()) } returns
            Result.success(dashboardWith(ReferenceSalary(monthlyCents = 300000, source = "declared")))

        viewModel = buildViewModel()
        advanceUntilIdle()

        val reference = viewModel.uiState.value.dashboard?.score?.referenceSalary
        assertEquals(300000, reference?.monthlyCents)
        assertEquals("declared", reference?.source)
    }

    @Test
    fun `a measured reference salary reaches the state with its source`() = runTest {
        coEvery { dashboardRepository.getDashboard(any(), any()) } returns
            Result.success(dashboardWith(ReferenceSalary(monthlyCents = 495000, source = "measured")))

        viewModel = buildViewModel()
        advanceUntilIdle()

        val reference = viewModel.uiState.value.dashboard?.score?.referenceSalary
        assertEquals(495000, reference?.monthlyCents)
        assertEquals("measured", reference?.source)
    }

    @Test
    fun `an unknown reference salary stays null, never 0`() = runTest {
        coEvery { dashboardRepository.getDashboard(any(), any()) } returns
            Result.success(dashboardWith(ReferenceSalary(monthlyCents = null, source = "unknown")))

        viewModel = buildViewModel()
        advanceUntilIdle()

        val reference = viewModel.uiState.value.dashboard?.score?.referenceSalary
        assertNull(reference?.monthlyCents)
        assertEquals("unknown", reference?.source)
    }

    @Test
    fun `a failed load leaves no reference salary to show`() = runTest {
        coEvery { dashboardRepository.getDashboard(any(), any()) } returns
            Result.failure(RuntimeException("boom"))

        viewModel = buildViewModel()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.dashboard?.score?.referenceSalary)
    }
}

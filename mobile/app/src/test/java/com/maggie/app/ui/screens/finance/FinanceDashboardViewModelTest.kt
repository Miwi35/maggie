package com.maggie.app.ui.screens.finance

import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureEvent
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.model.DashboardBalance
import com.maggie.app.data.model.FinanceDashboard
import com.maggie.app.data.model.DashboardScore
import com.maggie.app.data.model.FinanceScores
import com.maggie.app.data.model.PeriodScore
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
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class FinanceDashboardViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var dashboardRepository: FinanceDashboardRepository
    private lateinit var treasuryRepository: TreasuryRepository
    private lateinit var mercureService: MercureService
    private lateinit var authRepository: AuthRepository
    private val topics = mutableListOf<String>()
    private val mercureEvents = MutableSharedFlow<MercureEvent>(extraBufferCapacity = 8)
    private lateinit var viewModel: FinanceDashboardViewModel

    private val today = LocalDate.of(2026, 9, 17)

    private val scores = FinanceScores(date = "2026-09-17", week = PeriodScore(status = "green", label = "Dans le vert"))

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
        coEvery { dashboardRepository.getScores(any()) } returns Result.success(scores)
        mercureService = mockk()
        authRepository = mockk()
        coEvery { authRepository.getUserId() } returns "user-1"
        every { mercureService.subscribe(capture(topics)) } returns mercureEvents
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun buildViewModel() = FinanceDashboardViewModel(dashboardRepository, treasuryRepository, mercureService, authRepository, today)

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
        dashboard.copy(score = DashboardScore(referenceSalary = reference))

    @Test
    fun `a declared reference salary reaches the state with its source`() = runTest {
        coEvery { dashboardRepository.getDashboard(any(), any()) } returns
            Result.success(dashboardWith(ReferenceSalary(monthlyCents = 300000, source = "declared")))
        val reference = viewModel.uiState.value.dashboard?.score?.referenceSalary
        assertEquals(300000, reference?.monthlyCents)
        assertEquals("declared", reference?.source)
    }

    @Test
    fun `a measured reference salary reaches the state with its source`() = runTest {
        coEvery { dashboardRepository.getDashboard(any(), any()) } returns
            Result.success(dashboardWith(ReferenceSalary(monthlyCents = 495000, source = "measured")))
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

    @Test
    fun `the scores load with the dashboard, for today on the current month`() = runTest {
        viewModel = buildViewModel()
        assertTrue(viewModel.uiState.value.scores == null)

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(scores, state.scores)
        assertFalse(state.isLoadingScores)
        assertNull(state.scoresError)
        coVerify { dashboardRepository.getScores("2026-09-17") }
    }

    @Test
    fun `scores that fail to load leave the dashboard whole and say so`() = runTest {
        coEvery { dashboardRepository.getScores(any()) } returns Result.failure(RuntimeException("scores down"))
        val state = viewModel.uiState.value
        assertEquals("scores down", state.scoresError)
        assertNull(state.scores)
        assertFalse(state.isLoadingScores)
        assertEquals(950000, state.dashboard?.balance?.totalCents)
        assertNull(state.error)
    }

    @Test
    fun `a dashboard that fails to load leaves the scores whole`() = runTest {
        coEvery { dashboardRepository.getDashboard(any(), any()) } returns Result.failure(RuntimeException("boom"))
        assertEquals(scores, viewModel.uiState.value.scores)
        assertNull(viewModel.uiState.value.scoresError)
    }

    @Test
    fun `retrying after a failure loads the scores`() = runTest {
        coEvery { dashboardRepository.getScores(any()) } returns Result.failure(RuntimeException("scores down"))
        viewModel = buildViewModel()
        advanceUntilIdle()

        coEvery { dashboardRepository.getScores(any()) } returns Result.success(scores)
        viewModel.refreshScores()
        advanceUntilIdle()

        assertEquals(scores, viewModel.uiState.value.scores)
        assertNull(viewModel.uiState.value.scoresError)
    }

    @Test
    fun `a past month is scored on its last day`() = runTest {
        viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.shiftPeriod(-1)
        advanceUntilIdle()

        coVerify { dashboardRepository.getScores("2026-08-31") }
    }

    @Test
    fun `a future month is scored on its first day`() = runTest {
        viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.shiftPeriod(1)
        advanceUntilIdle()

        coVerify { dashboardRepository.getScores("2026-10-01") }
    }

    @Test
    fun `the scores of the month left do not stay on screen while the new ones load`() = runTest {
        viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.shiftPeriod(-1)

        assertNull(viewModel.uiState.value.scores)
    }

    @Test
    fun `scoresDate picks the day inside the month shown`() {
        assertEquals(today, scoresDate(today, 2026, 9))
        assertEquals(LocalDate.of(2024, 2, 29), scoresDate(today, 2024, 2))
        assertEquals(LocalDate.of(2027, 1, 1), scoresDate(today, 2027, 1))
    }

    @Test
    fun `a transaction or an envelope pushed by Mercure reloads the scores`() = runTest {
        viewModel = buildViewModel()
        advanceUntilIdle()
        assertEquals(
            setOf("/users/user-1/api/transactions/{id}", "/users/user-1/api/envelopes/{id}"),
            topics.toSet(),
        )

        val updated = scores.copy(week = PeriodScore(status = "red", label = "Dépassement"))
        coEvery { dashboardRepository.getScores(any()) } returns Result.success(updated)
        mercureEvents.tryEmit(MercureEvent(data = "{}"))
        advanceUntilIdle()

        assertEquals(updated, viewModel.uiState.value.scores)
        coVerify(atLeast = 2) { dashboardRepository.getScores("2026-09-17") }
        assertNotNull(viewModel.uiState.value.dashboard)
    }
}

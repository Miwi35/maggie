package com.maggie.app.ui.screens.finance

import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureEvent
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.model.AccountsTile
import com.maggie.app.data.model.FinanceDashboard
import com.maggie.app.data.model.FinanceScores
import com.maggie.app.data.model.PeriodScore
import com.maggie.app.data.model.BudgetTile
import com.maggie.app.data.model.ExpectedSalary
import com.maggie.app.data.model.FinanceTiles
import com.maggie.app.data.model.FinanceTodo
import com.maggie.app.data.model.BalanceBeforeSalary
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
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
        scores = scores,
        tiles = FinanceTiles(
            accounts = AccountsTile(totalCents = 950000, cushionCents = 750000, availableCents = 200000),
            budget = BudgetTile(expectedSalary = ExpectedSalary(monthlyCents = 300000, source = "declared")),
        ),
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        dashboardRepository = mockk()
        treasuryRepository = mockk()
        coEvery { treasuryRepository.getOutlook() } returns Result.success(TreasuryOutlook())
        coEvery { dashboardRepository.getDashboard(any(), any()) } returns Result.success(dashboard)
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
        assertEquals(950000, state.dashboard?.tiles?.accounts?.totalCents)
        assertEquals(scores, state.dashboard?.scores)
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
        assertEquals(950000, state.dashboard?.tiles?.accounts?.totalCents)
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

    @Test
    fun `the expected salary of the tile reaches the state, unknown staying null`() = runTest {
        viewModel = buildViewModel()
        advanceUntilIdle()
        assertEquals(300000, viewModel.uiState.value.dashboard?.tiles?.budget?.expectedSalary?.monthlyCents)

        coEvery { dashboardRepository.getDashboard(any(), any()) } returns
            Result.success(dashboard.copy(tiles = FinanceTiles(budget = BudgetTile(expectedSalary = null))))
        viewModel.refresh()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.dashboard?.tiles?.budget?.expectedSalary)
    }

    @Test
    fun `the dashboard is loading until the answer comes`() = runTest {
        val answer = CompletableDeferred<Result<FinanceDashboard>>()
        coEvery { dashboardRepository.getDashboard(any(), any()) } coAnswers { answer.await() }

        viewModel = buildViewModel()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isLoading)
        assertNull(viewModel.uiState.value.dashboard)

        answer.complete(Result.success(dashboard))
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isLoading)
        assertEquals(dashboard, viewModel.uiState.value.dashboard)
    }

    @Test
    fun `clearError drops the error and keeps the dashboard`() = runTest {
        viewModel = buildViewModel()
        advanceUntilIdle()
        coEvery { dashboardRepository.getDashboard(any(), any()) } returns Result.failure(RuntimeException("boom"))
        viewModel.refresh()
        advanceUntilIdle()
        assertEquals("boom", viewModel.uiState.value.error)

        viewModel.clearError()

        assertNull(viewModel.uiState.value.error)
        assertNotNull(viewModel.uiState.value.dashboard)
    }

    @Test
    fun `the figures of the month left do not stay on screen while the new ones load`() = runTest {
        viewModel = buildViewModel()
        advanceUntilIdle()
        val answer = CompletableDeferred<Result<FinanceDashboard>>()
        coEvery { dashboardRepository.getDashboard(2026, 8) } coAnswers { answer.await() }

        viewModel.shiftPeriod(-1)

        val state = viewModel.uiState.value
        assertEquals(8, state.month)
        assertNull(state.dashboard)
        assertTrue(state.isLoading)
    }

    @Test
    fun `a failure on the new month does not keep the old month's figures`() = runTest {
        viewModel = buildViewModel()
        advanceUntilIdle()
        coEvery { dashboardRepository.getDashboard(2026, 8) } returns Result.failure(RuntimeException("boom"))

        viewModel.shiftPeriod(-1)
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.dashboard)
        assertEquals("boom", viewModel.uiState.value.error)
    }

    @Test
    fun `a late answer for the month left does not overwrite the month now shown`() = runTest {
        val late = CompletableDeferred<Result<FinanceDashboard>>()
        val august = dashboard.copy(month = 8, tiles = FinanceTiles(accounts = AccountsTile(totalCents = 1)))
        // An answer that ignores the cancellation: only the check on arrival can keep it out.
        coEvery { dashboardRepository.getDashboard(2026, 9) } coAnswers { withContext(NonCancellable) { late.await() } }
        coEvery { dashboardRepository.getDashboard(2026, 8) } returns Result.success(august)
        viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.shiftPeriod(-1)
        advanceUntilIdle()
        late.complete(Result.success(dashboard))
        advanceUntilIdle()

        assertEquals(8, viewModel.uiState.value.month)
        assertEquals(august, viewModel.uiState.value.dashboard)
        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test
    fun `an account, a transaction or an envelope pushed by Mercure reloads the dashboard`() = runTest {
        viewModel = buildViewModel()
        advanceUntilIdle()
        assertEquals(
            setOf(
                "/users/user-1/api/accounts/{id}",
                "/users/user-1/api/transactions/{id}",
                "/users/user-1/api/envelopes/{id}",
            ),
            topics.toSet(),
        )

        val updated = dashboard.copy(
            scores = scores.copy(week = PeriodScore(status = "red", label = "Dépassement")),
            tiles = FinanceTiles(accounts = AccountsTile(totalCents = 1)),
        )
        coEvery { dashboardRepository.getDashboard(any(), any()) } returns Result.success(updated)
        mercureEvents.tryEmit(MercureEvent(data = "{}"))
        advanceUntilIdle()

        assertEquals(updated, viewModel.uiState.value.dashboard)
        coVerify(atLeast = 2) { dashboardRepository.getDashboard(2026, 9) }
    }

    @Test
    fun `what is to do reaches the state, and a pushed change that settles it empties it`() = runTest {
        val todo = FinanceTodo(toSortCount = 3, rejectionsToSettleCount = 1, reviewReady = true, advanceToRepayCents = 80000)
        coEvery { dashboardRepository.getDashboard(any(), any()) } returns Result.success(dashboard.copy(todo = todo))
        viewModel = buildViewModel()
        advanceUntilIdle()
        assertEquals(todo, viewModel.uiState.value.dashboard?.todo)

        coEvery { dashboardRepository.getDashboard(any(), any()) } returns Result.success(dashboard)
        mercureEvents.tryEmit(MercureEvent(data = "{}"))
        advanceUntilIdle()

        assertEquals(FinanceTodo(), viewModel.uiState.value.dashboard?.todo)
    }

    @Test
    fun `a server that sends no todo block leaves nothing to do`() = runTest {
        viewModel = buildViewModel()
        advanceUntilIdle()

        assertEquals(FinanceTodo(), viewModel.uiState.value.dashboard?.todo)
    }
}

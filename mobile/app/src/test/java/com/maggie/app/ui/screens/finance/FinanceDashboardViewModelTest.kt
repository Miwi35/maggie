package com.maggie.app.ui.screens.finance

import com.maggie.app.data.model.DashboardBalance
import com.maggie.app.data.model.FinanceDashboard
import com.maggie.app.data.model.MonthlyFlow
import com.maggie.app.data.model.SavingCapacity
import com.maggie.app.data.repository.FinanceDashboardRepository
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
        coEvery { dashboardRepository.getDashboard(any(), any()) } returns Result.success(dashboard)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun buildViewModel() = FinanceDashboardViewModel(dashboardRepository, today)

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
}

package com.maggie.app.ui.screens.finance

import com.maggie.app.data.api.LoanCreateRequest
import com.maggie.app.data.model.DebtTimeline
import com.maggie.app.data.model.Loan
import com.maggie.app.data.model.MonthlyRelief
import com.maggie.app.data.model.SavingCapacity
import com.maggie.app.data.repository.LoanRepository
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

@OptIn(ExperimentalCoroutinesApi::class)
class LoanViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var loanRepository: LoanRepository
    private lateinit var viewModel: LoanViewModel

    private val loans = listOf(
        Loan(id = "loan-1", name = "Crédit auto", principalRemainingCents = 240000, monthlyPaymentCents = 20000, priority = 5),
        Loan(id = "loan-2", name = "Prêt étudiant", principalRemainingCents = 120000, monthlyPaymentCents = 40000, priority = 10),
    )

    private val timeline = DebtTimeline(
        horizonMonths = 60,
        totalPrincipalRemainingCents = 360000,
        totalMonthlyPaymentCents = 60000,
        reliefByMonth = listOf(
            MonthlyRelief(month = "2026-12", freedCents = 40000, cumulativeFreedCents = 40000),
        ),
        savingCapacity = SavingCapacity(
            monthlyNetIncomeCents = 350000,
            loanPaymentsCents = 60000,
            netCapacityCents = 290000,
            isIncomeKnown = true,
        ),
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        loanRepository = mockk()
        coEvery { loanRepository.getLoans() } returns Result.success(loans)
        coEvery { loanRepository.getTimeline(any()) } returns Result.success(timeline)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial load sorts loans by priority and brings the timeline`() = runTest {
        viewModel = LoanViewModel(loanRepository)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("Prêt étudiant", state.loans[0].name)
        assertEquals(290000, state.timeline?.savingCapacity?.netCapacityCents)
        assertFalse(state.isLoading)
        assertNull(state.error)
    }

    @Test
    fun `a failing timeline does not cost the user their loan list`() = runTest {
        coEvery { loanRepository.getTimeline(any()) } returns Result.failure(RuntimeException("boom"))

        viewModel = LoanViewModel(loanRepository)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(2, state.loans.size)
        assertNull(state.timeline)
        assertNull(state.error)
    }

    @Test
    fun `createLoan dispatches the request and refreshes`() = runTest {
        coEvery { loanRepository.createLoan(any()) } returns Result.success(loans[0])

        viewModel = LoanViewModel(loanRepository)
        advanceUntilIdle()

        viewModel.createLoan(
            LoanCreateRequest(name = "Travaux", principalRemainingCents = 600000, monthlyPaymentCents = 50000),
        )
        advanceUntilIdle()

        coVerify { loanRepository.createLoan(any()) }
        coVerify(atLeast = 2) { loanRepository.getLoans() }
    }

    @Test
    fun `deleteLoan refreshes the list`() = runTest {
        coEvery { loanRepository.deleteLoan("loan-1") } returns Result.success(Unit)

        viewModel = LoanViewModel(loanRepository)
        advanceUntilIdle()

        viewModel.deleteLoan("loan-1")
        advanceUntilIdle()

        coVerify { loanRepository.deleteLoan("loan-1") }
        coVerify(atLeast = 2) { loanRepository.getLoans() }
    }

    @Test
    fun `load failure sets error`() = runTest {
        coEvery { loanRepository.getLoans() } returns Result.failure(RuntimeException("boom"))

        viewModel = LoanViewModel(loanRepository)
        advanceUntilIdle()

        assertEquals("boom", viewModel.uiState.value.error)
    }
}

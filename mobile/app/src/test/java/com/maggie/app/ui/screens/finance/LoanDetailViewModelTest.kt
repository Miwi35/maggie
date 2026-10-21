package com.maggie.app.ui.screens.finance

import com.maggie.app.data.model.EarlyRepayment
import com.maggie.app.data.model.Loan
import com.maggie.app.data.model.LoanCurve
import com.maggie.app.data.model.LoanCurvePoint
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LoanDetailViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var repository: LoanRepository
    private lateinit var viewModel: LoanDetailViewModel

    private val loan = Loan(
        id = "loan-1",
        name = "Prêt immo",
        principalRemainingCents = 9800000,
        monthlyPaymentCents = 62500,
        annualRateBasisPoints = 180,
    )
    private val curve = LoanCurve(
        principalRemainingCents = 9800000,
        freedOn = "2034-03",
        monthsRemaining = 89,
        totalInterestCents = 620000,
        months = listOf(LoanCurvePoint("2026-11", 9740000)),
    )
    private val total = EarlyRepayment(
        amountCents = 9800000,
        penaltyCents = 88200,
        remainingInterestCents = 620000,
        realGainCents = 531800,
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        repository = mockk()
        coEvery { repository.getLoan("loan-1") } returns Result.success(loan)
        coEvery { repository.getCurve("loan-1") } returns Result.success(curve)
        coEvery { repository.getEarlyRepayment("loan-1", null) } returns Result.success(total)
        viewModel = LoanDetailViewModel(repository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `loading a loan marks it loading before anything arrives`() = runTest(testDispatcher) {
        viewModel.load("loan-1")

        assertTrue(viewModel.uiState.value.isLoading)
        assertNull(viewModel.uiState.value.loan)
    }

    @Test
    fun `a loaded loan comes with its curve, its total IRA and its draft`() = runTest(testDispatcher) {
        viewModel.load("loan-1")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals(loan, state.loan)
        assertEquals(curve, state.curve)
        assertEquals(total, state.total)
        assertEquals("1,80", state.draft?.rate)
        assertEquals("625,00", state.draft?.payment)
        assertNull(state.error)
    }

    @Test
    fun `a loan that cannot be read says so`() = runTest(testDispatcher) {
        coEvery { repository.getLoan("loan-1") } returns Result.failure(RuntimeException("Introuvable"))

        viewModel.load("loan-1")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals("Introuvable", state.error)
        assertNull(state.loan)
    }

    @Test
    fun `a missing schedule does not cost the loan`() = runTest(testDispatcher) {
        coEvery { repository.getCurve("loan-1") } returns Result.failure(RuntimeException("boom"))

        viewModel.load("loan-1")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(loan, state.loan)
        assertNull(state.curve)
        assertNull(state.error)
    }

    @Test
    fun `a loan that forbids early repayment keeps the server's sentence instead of a total`() = runTest(testDispatcher) {
        coEvery { repository.getEarlyRepayment("loan-1", null) } returns Result.failure(RuntimeException("Remboursement anticipé non autorisé"))

        viewModel.load("loan-1")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNull(state.total)
        assertEquals("Remboursement anticipé non autorisé", state.totalError)
    }

    @Test
    fun `editing the draft clears the saved mark`() = runTest(testDispatcher) {
        viewModel.load("loan-1")
        advanceUntilIdle()

        viewModel.editDraft(viewModel.uiState.value.draft!!.copy(rate = "2,10"))

        assertEquals("2,10", viewModel.uiState.value.draft?.rate)
        assertFalse(viewModel.uiState.value.saved)
    }

    @Test
    fun `saving patches the loan and reads the curve again`() = runTest(testDispatcher) {
        val updated = loan.copy(annualRateBasisPoints = 210)
        coEvery { repository.updateLoan("loan-1", any()) } returns Result.success(updated)
        viewModel.load("loan-1")
        advanceUntilIdle()

        viewModel.editDraft(viewModel.uiState.value.draft!!.copy(rate = "2,10"))
        viewModel.save()
        assertTrue(viewModel.uiState.value.isSaving)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isSaving)
        assertTrue(state.saved)
        assertEquals(updated, state.loan)
        assertEquals("2,10", state.draft?.rate)
        coVerify { repository.updateLoan("loan-1", match { it.annualRateBasisPoints == 210 }) }
        coVerify(exactly = 2) { repository.getCurve("loan-1") }
    }

    @Test
    fun `a refused save shows the server's message as it is and keeps the draft`() = runTest(testDispatcher) {
        coEvery { repository.updateLoan("loan-1", any()) } returns Result.failure(RuntimeException("Le plafond doit dépasser le plancher"))
        viewModel.load("loan-1")
        advanceUntilIdle()

        viewModel.editDraft(viewModel.uiState.value.draft!!.copy(rate = "2,10"))
        viewModel.save()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isSaving)
        assertFalse(state.saved)
        assertEquals("Le plafond doit dépasser le plancher", state.saveError)
        assertEquals("2,10", state.draft?.rate)
    }

    @Test
    fun `a draft with a typed value that is not a number is not sent`() = runTest(testDispatcher) {
        viewModel.load("loan-1")
        advanceUntilIdle()

        viewModel.editDraft(viewModel.uiState.value.draft!!.copy(rate = "abc"))
        viewModel.save()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isSaving)
        coVerify(exactly = 0) { repository.updateLoan(any(), any()) }
    }

    @Test
    fun `simulating an amount asks for that amount and keeps the result`() = runTest(testDispatcher) {
        val partial = total.copy(amountCents = 2000000, penaltyCents = 18000)
        coEvery { repository.getEarlyRepayment("loan-1", 2000000) } returns Result.success(partial)
        viewModel.load("loan-1")
        advanceUntilIdle()

        viewModel.simulate(2000000)
        assertTrue(viewModel.uiState.value.isSimulating)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isSimulating)
        assertEquals(partial, state.simulation)
        assertNull(state.simulationError)
    }

    @Test
    fun `a refused simulation shows the server's error as it is`() = runTest(testDispatcher) {
        coEvery { repository.getEarlyRepayment("loan-1", 50) } returns Result.failure(RuntimeException("Montant inférieur au minimum"))
        viewModel.load("loan-1")
        advanceUntilIdle()

        viewModel.simulate(50)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isSimulating)
        assertNull(state.simulation)
        assertEquals("Montant inférieur au minimum", state.simulationError)
    }

    @Test
    fun `a save drops the simulation made on the old conditions`() = runTest(testDispatcher) {
        coEvery { repository.updateLoan("loan-1", any()) } returns Result.success(loan)
        viewModel.load("loan-1")
        advanceUntilIdle()
        viewModel.simulate(null)
        advanceUntilIdle()
        assertNotNull(viewModel.uiState.value.simulation)

        viewModel.save()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.simulation)
    }
}

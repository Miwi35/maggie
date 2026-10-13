package com.maggie.app.ui.screens.finance

import com.maggie.app.data.api.TransactionCreateRequest
import com.maggie.app.data.model.AdvanceRepaymentProposal
import com.maggie.app.data.model.SalaryDelay
import com.maggie.app.data.model.SavingsAdvance
import com.maggie.app.data.model.SavingsAdvances
import com.maggie.app.data.model.SavingsSource
import com.maggie.app.data.model.Transaction
import com.maggie.app.data.repository.SavingsAdvancesRepository
import com.maggie.app.data.repository.TransactionRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class SavingsAdvancesViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var advancesRepository: SavingsAdvancesRepository
    private lateinit var transactionRepository: TransactionRepository

    private val proposal = AdvanceRepaymentProposal(
        fromAccountId = "acc-checking",
        toAccountId = "acc-livret",
        amountCents = 50000,
        advanceId = "adv-1",
        label = "Remise avance Livret A",
        currency = "EUR",
    )

    private val loaded = SavingsAdvances(
        advances = listOf(
            SavingsAdvance(
                id = "adv-1",
                sourceAccountId = "acc-livret",
                sourceAccountName = "Livret A",
                amountCents = 150000,
                openedOn = "2026-10-11",
                repaidCents = 100000,
                outstandingCents = 50000,
            ),
            SavingsAdvance(
                id = "adv-0",
                sourceAccountId = "acc-livret",
                sourceAccountName = "Livret A",
                amountCents = 20000,
                openedOn = "2026-08-02",
                repaidCents = 20000,
                isClosed = true,
            ),
        ),
        bySource = listOf(SavingsSource("acc-livret", "Livret A", 50000, 1)),
        totalOutstandingCents = 50000,
        salaryDelay = SalaryDelay(isConfigured = true, salaryExpectedDay = 5, referenceCents = 250000, isLate = true, delayDays = 6),
        proposals = listOf(proposal),
        proposalsTotalCents = 50000,
    )

    private fun viewModel() = SavingsAdvancesViewModel(advancesRepository, transactionRepository) { LocalDate.of(2026, 10, 13) }

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        advancesRepository = mockk()
        transactionRepository = mockk()
        coEvery { advancesRepository.getSavingsAdvances() } returns Result.success(loaded)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial load exposes the advances`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(loaded, state.advances)
        assertEquals(1, state.advances!!.openAdvances.size)
        assertEquals(1, state.advances!!.closedAdvances.size)
        assertFalse(state.isLoading)
        assertNull(state.loadError)
    }

    @Test
    fun `load failure exposes the error and no advances`() = runTest {
        coEvery { advancesRepository.getSavingsAdvances() } returns Result.failure(RuntimeException("boom"))

        val viewModel = viewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("boom", state.loadError)
        assertNull(state.advances)
        assertFalse(state.isLoading)
    }

    @Test
    fun `retry after a failed load shows the advances`() = runTest {
        coEvery { advancesRepository.getSavingsAdvances() } returns Result.failure(RuntimeException("boom"))
        val viewModel = viewModel()
        advanceUntilIdle()

        coEvery { advancesRepository.getSavingsAdvances() } returns Result.success(loaded)
        viewModel.refresh()
        advanceUntilIdle()

        assertEquals(loaded, viewModel.uiState.value.advances)
        assertNull(viewModel.uiState.value.loadError)
    }

    @Test
    fun `no advance leaves the lists empty without an error`() = runTest {
        coEvery { advancesRepository.getSavingsAdvances() } returns Result.success(SavingsAdvances())

        val viewModel = viewModel()
        advanceUntilIdle()

        val advances = viewModel.uiState.value.advances!!
        assertTrue(advances.openAdvances.isEmpty())
        assertTrue(advances.proposals.isEmpty())
        assertEquals(0, advances.totalOutstandingCents)
        assertNull(viewModel.uiState.value.loadError)
    }

    @Test
    fun `an unset salary day is exposed as null so the screen asks for it`() = runTest {
        coEvery { advancesRepository.getSavingsAdvances() } returns
            Result.success(loaded.copy(salaryDelay = SalaryDelay()))

        val viewModel = viewModel()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.advances!!.salaryDelay.salaryExpectedDay)
    }

    @Test
    fun `saving the salary day keeps the delay the API recomputed`() = runTest {
        val delay = SalaryDelay(isConfigured = true, salaryExpectedDay = 28, referenceCents = 250000)
        coEvery { advancesRepository.setSalaryExpectedDay(28) } returns Result.success(delay)
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.saveSalaryDay(" 28 ")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(delay, state.advances!!.salaryDelay)
        assertEquals("Jour de salaire enregistré", state.message)
        assertFalse(state.isSavingDay)
        assertNull(state.actionError)
        coVerify(exactly = 1) { advancesRepository.setSalaryExpectedDay(28) }
    }

    @Test
    fun `an empty field forgets the salary day`() = runTest {
        coEvery { advancesRepository.setSalaryExpectedDay(null) } returns Result.success(SalaryDelay())
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.saveSalaryDay("")
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.advances!!.salaryDelay.salaryExpectedDay)
        coVerify { advancesRepository.setSalaryExpectedDay(null) }
    }

    @Test
    fun `a day out of 1 to 31 is refused before any request`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.saveSalaryDay("32")
        advanceUntilIdle()

        assertEquals("Indiquez un jour entre 1 et 31.", viewModel.uiState.value.actionError)
        coVerify(exactly = 0) { advancesRepository.setSalaryExpectedDay(any()) }
    }

    @Test
    fun `saving the salary day failure surfaces the error and keeps the previous delay`() = runTest {
        coEvery { advancesRepository.setSalaryExpectedDay(any()) } returns Result.failure(RuntimeException("refusé"))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.saveSalaryDay("12")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("refusé", state.actionError)
        assertFalse(state.isSavingDay)
        assertEquals(loaded.salaryDelay, state.advances!!.salaryDelay)
    }

    @Test
    fun `je l'ai fait books the debit leg then the credit leg, then reloads`() = runTest {
        coEvery { transactionRepository.createTransaction(any()) } returns Result.success(Transaction(id = "t", label = "x"))
        val viewModel = viewModel()
        advanceUntilIdle()
        val reloaded = loaded.copy(proposals = emptyList(), totalOutstandingCents = 0)
        coEvery { advancesRepository.getSavingsAdvances() } returns Result.success(reloaded)

        viewModel.recordCounterTransfer(proposal)
        advanceUntilIdle()

        coVerifyOrder {
            transactionRepository.createTransaction(
                TransactionCreateRequest(
                    account = "/api/accounts/acc-checking",
                    amountCents = -50000,
                    label = "Remise avance Livret A",
                    currency = "EUR",
                    status = "spent",
                    bookedAt = "2026-10-13",
                ),
            )
            transactionRepository.createTransaction(
                TransactionCreateRequest(
                    account = "/api/accounts/acc-livret",
                    amountCents = 50000,
                    label = "Remise avance Livret A",
                    currency = "EUR",
                    status = "spent",
                    bookedAt = "2026-10-13",
                ),
            )
            advancesRepository.getSavingsAdvances()
        }
        val state = viewModel.uiState.value
        assertEquals(reloaded, state.advances)
        assertEquals("Transaction enregistrée", state.message)
        assertNull(state.actionError)
        assertNull(state.recordingAdvanceId)
    }

    @Test
    fun `a failing credit leg shows the error and reloads`() = runTest {
        coEvery { transactionRepository.createTransaction(match { it.amountCents < 0 }) } returns
            Result.success(Transaction(id = "t", label = "x"))
        coEvery { transactionRepository.createTransaction(match { it.amountCents > 0 }) } returns
            Result.failure(RuntimeException("compte fermé"))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.recordCounterTransfer(proposal)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("Le débit est enregistré, pas le crédit : compte fermé", state.actionError)
        assertNull(state.message)
        assertNull(state.recordingAdvanceId)
        coVerify(exactly = 2) { advancesRepository.getSavingsAdvances() }
    }

    @Test
    fun `a failing debit leg books nothing more and reloads`() = runTest {
        coEvery { transactionRepository.createTransaction(any()) } returns Result.failure(RuntimeException("boom"))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.recordCounterTransfer(proposal)
        advanceUntilIdle()

        assertEquals("boom", viewModel.uiState.value.actionError)
        coVerify(exactly = 1) { transactionRepository.createTransaction(any()) }
        coVerify(exactly = 2) { advancesRepository.getSavingsAdvances() }
    }

    @Test
    fun `a second tap while a counter-transfer is being booked is ignored`() = runTest {
        coEvery { transactionRepository.createTransaction(any()) } returns Result.success(Transaction(id = "t", label = "x"))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.recordCounterTransfer(proposal)
        viewModel.recordCounterTransfer(proposal)
        advanceUntilIdle()

        coVerify(exactly = 2) { transactionRepository.createTransaction(any()) }
    }
}

package com.maggie.app.ui.screens.finance

import com.maggie.app.data.model.Transaction
import com.maggie.app.data.repository.TransactionRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
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
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TransactionViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var transactionRepository: TransactionRepository
    private lateinit var viewModel: TransactionViewModel

    private val accountId = "acc-1"
    private val sampleTransactions = listOf(
        Transaction(id = "tx-1", label = "Supermarché", amountCents = -4599),
        Transaction(id = "tx-2", label = "Salaire", amountCents = 250000),
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        transactionRepository = mockk()
        coEvery { transactionRepository.getTransactions(accountId) } returns Result.success(sampleTransactions)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial load fetches transactions scoped to the account`() = runTest {
        viewModel = TransactionViewModel(transactionRepository, accountId)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(2, state.transactions.size)
        assertFalse(state.isLoading)
        coVerify { transactionRepository.getTransactions(accountId) }
    }

    @Test
    fun `createTransaction posts to this account with a signed amount`() = runTest {
        val request = slot<com.maggie.app.data.api.TransactionCreateRequest>()
        coEvery { transactionRepository.createTransaction(capture(request)) } returns Result.success(
            Transaction(id = "tx-3", label = "Boulangerie", amountCents = -1599),
        )
        viewModel = TransactionViewModel(transactionRepository, accountId)
        advanceUntilIdle()

        viewModel.createTransaction(amountCents = -1599, label = "Boulangerie")
        advanceUntilIdle()

        assertEquals("/api/accounts/$accountId", request.captured.account)
        assertEquals(-1599, request.captured.amountCents)
        coVerify(atLeast = 2) { transactionRepository.getTransactions(accountId) }
    }

    @Test
    fun `deleteTransaction removes it from state`() = runTest {
        coEvery { transactionRepository.deleteTransaction("tx-1") } returns Result.success(Unit)
        viewModel = TransactionViewModel(transactionRepository, accountId)
        advanceUntilIdle()

        viewModel.deleteTransaction("tx-1")
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.transactions.size)
        assertEquals("tx-2", viewModel.uiState.value.transactions[0].id)
    }
}

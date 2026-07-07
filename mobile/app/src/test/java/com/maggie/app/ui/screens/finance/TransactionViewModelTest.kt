package com.maggie.app.ui.screens.finance

import com.maggie.app.data.api.TransactionCreateRequest
import com.maggie.app.data.model.Account
import com.maggie.app.data.model.Transaction
import com.maggie.app.data.repository.AccountRepository
import com.maggie.app.data.repository.TransactionRepository
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
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TransactionViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var transactionRepository: TransactionRepository
    private lateinit var accountRepository: AccountRepository
    private lateinit var viewModel: TransactionViewModel

    private val sampleTransactions = listOf(
        Transaction(id = "tx-1", label = "Supermarché", amountCents = -4599),
        Transaction(id = "tx-2", label = "Salaire", amountCents = 250000),
    )
    private val sampleAccounts = listOf(
        Account(id = "acc-1", name = "Compte courant"),
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        transactionRepository = mockk()
        accountRepository = mockk()
        coEvery { transactionRepository.getTransactions() } returns Result.success(sampleTransactions)
        coEvery { accountRepository.getAccounts() } returns Result.success(sampleAccounts)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial load populates transactions and accounts`() = runTest {
        viewModel = TransactionViewModel(transactionRepository, accountRepository)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(2, state.transactions.size)
        assertEquals(1, state.accounts.size)
        assertFalse(state.isLoading)
    }

    @Test
    fun `createTransaction dispatches request and refreshes`() = runTest {
        coEvery { transactionRepository.createTransaction(any()) } returns Result.success(
            Transaction(id = "tx-3", label = "Boulangerie", amountCents = -1599),
        )
        viewModel = TransactionViewModel(transactionRepository, accountRepository)
        advanceUntilIdle()

        viewModel.createTransaction(
            TransactionCreateRequest(account = "/api/accounts/acc-1", amountCents = -1599, label = "Boulangerie"),
        )
        advanceUntilIdle()

        coVerify { transactionRepository.createTransaction(any()) }
        coVerify(atLeast = 2) { transactionRepository.getTransactions() }
    }

    @Test
    fun `deleteTransaction removes it from state`() = runTest {
        coEvery { transactionRepository.deleteTransaction("tx-1") } returns Result.success(Unit)
        viewModel = TransactionViewModel(transactionRepository, accountRepository)
        advanceUntilIdle()

        viewModel.deleteTransaction("tx-1")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(1, state.transactions.size)
        assertEquals("tx-2", state.transactions[0].id)
    }
}

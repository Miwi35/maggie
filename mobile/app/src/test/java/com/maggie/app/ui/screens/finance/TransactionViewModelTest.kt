package com.maggie.app.ui.screens.finance

import com.maggie.app.data.api.TransactionCreateRequest
import com.maggie.app.data.model.Category
import com.maggie.app.data.model.Transaction
import com.maggie.app.data.repository.CategoryRepository
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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TransactionViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var transactionRepository: TransactionRepository
    private lateinit var categoryRepository: CategoryRepository
    private lateinit var viewModel: TransactionViewModel

    private val accountId = "acc-1"
    private val sampleTransactions = listOf(
        Transaction(id = "tx-1", label = "Supermarché", amountCents = -4599),
        Transaction(id = "tx-2", label = "Salaire", amountCents = 250000),
    )

    private val sampleCategories = listOf(
        Category(id = "cat-1", name = "Courses"),
        Category(id = "cat-2", name = "Loisirs"),
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        transactionRepository = mockk()
        categoryRepository = mockk()
        coEvery { categoryRepository.getCategories() } returns Result.success(sampleCategories)
        coEvery { transactionRepository.getTransactions(accountId) } returns Result.success(sampleTransactions)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial load fetches transactions scoped to the account`() = runTest {
        viewModel = TransactionViewModel(transactionRepository, categoryRepository, accountId)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(2, state.transactions.size)
        assertFalse(state.isLoading)
        coVerify { transactionRepository.getTransactions(accountId) }
    }

    @Test
    fun `createTransaction posts to this account with a signed amount`() = runTest {
        val request = slot<TransactionCreateRequest>()
        coEvery { transactionRepository.createTransaction(capture(request)) } returns Result.success(
            Transaction(id = "tx-3", label = "Boulangerie", amountCents = -1599),
        )
        viewModel = TransactionViewModel(transactionRepository, categoryRepository, accountId)
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
        viewModel = TransactionViewModel(transactionRepository, categoryRepository, accountId)
        advanceUntilIdle()

        viewModel.deleteTransaction("tx-1")
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.transactions.size)
        assertEquals("tx-2", viewModel.uiState.value.transactions[0].id)
    }

    @Test
    fun `categories are loaded so the form can offer them`() = runTest {
        viewModel = TransactionViewModel(transactionRepository, categoryRepository, accountId)
        advanceUntilIdle()

        assertEquals(listOf("Courses", "Loisirs"), viewModel.uiState.value.categories.map { it.name })
    }

    @Test
    fun `a failing category load does not break the transaction list`() = runTest {
        coEvery { categoryRepository.getCategories() } returns Result.failure(RuntimeException("offline"))
        viewModel = TransactionViewModel(transactionRepository, categoryRepository, accountId)
        advanceUntilIdle()

        assertEquals(2, viewModel.uiState.value.transactions.size)
        assertEquals(emptyList<Category>(), viewModel.uiState.value.categories)
    }

    @Test
    fun `createTransaction sends the chosen status, category and date`() = runTest {
        val request = slot<TransactionCreateRequest>()
        coEvery { transactionRepository.createTransaction(capture(request)) } returns Result.success(
            Transaction(id = "tx-4", label = "Cinéma", amountCents = -1200),
        )
        viewModel = TransactionViewModel(transactionRepository, categoryRepository, accountId)
        advanceUntilIdle()

        viewModel.createTransaction(
            amountCents = -1200,
            label = "Cinéma",
            status = "planned",
            categoryId = "cat-2",
            bookedAt = "2026-11-03",
        )
        advanceUntilIdle()

        assertEquals("planned", request.captured.status)
        assertEquals("/api/categories/cat-2", request.captured.category)
        assertEquals("2026-11-03", request.captured.bookedAt)
    }

    @Test
    fun `createTransaction without choices leaves the API defaults, spent today without category`() = runTest {
        val request = slot<TransactionCreateRequest>()
        coEvery { transactionRepository.createTransaction(capture(request)) } returns Result.success(
            Transaction(id = "tx-5", label = "Pain", amountCents = -150),
        )
        viewModel = TransactionViewModel(transactionRepository, categoryRepository, accountId)
        advanceUntilIdle()

        viewModel.createTransaction(amountCents = -150, label = "Pain")
        advanceUntilIdle()

        assertEquals("spent", request.captured.status)
        assertNull(request.captured.category)
        assertNull(request.captured.bookedAt)
    }
}

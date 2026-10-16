package com.maggie.app.ui.screens.finance

import com.maggie.app.data.model.Category
import com.maggie.app.data.model.SortResult
import com.maggie.app.data.model.SortSuggestion
import com.maggie.app.data.model.SortedRule
import com.maggie.app.data.model.ToSortPile
import com.maggie.app.data.model.TransactionToSort
import com.maggie.app.data.model.TransferLeg
import com.maggie.app.data.repository.CategoryRepository
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ToSortViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var transactions: TransactionRepository
    private lateinit var categories: CategoryRepository

    private val carrefour = TransactionToSort(
        id = "tx-1",
        label = "CARREFOUR MARKET 1234",
        amountCents = -5320,
        bookedAt = "2026-10-12",
        accountName = "Courant",
        suggestion = SortSuggestion("cat-courses", "Alimentation › Courses", "rule", "Règle « contient CARREFOUR »"),
    )

    private val unknown = TransactionToSort(id = "tx-2", label = "DUPONT MARTIN", amountCents = -1200, bookedAt = "2026-10-11")

    private val categoryList = listOf(
        Category(id = "cat-food", name = "Alimentation"),
        Category(id = "cat-courses", name = "Courses", parent = "/api/categories/cat-food"),
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        transactions = mockk()
        categories = mockk()
        coEvery { categories.getCategories() } returns Result.success(categoryList)
        coEvery { transactions.getToSort() } returns Result.success(ToSortPile(2, listOf(carrefour, unknown)))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun load() = ToSortViewModel(transactions, categories)

    @Test
    fun `the pile is loading until the server has answered`() = runTest {
        val viewModel = load()

        assertTrue(viewModel.uiState.value.isLoading)
        assertFalse(viewModel.uiState.value.loaded)
        assertNull(viewModel.uiState.value.current)

        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test
    fun `the newest line is the card, with its suggestion and the whole count`() = runTest {
        val viewModel = load()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.loaded)
        assertEquals("tx-1", state.current?.id)
        assertEquals("Règle « contient CARREFOUR »", state.current?.suggestion?.reason)
        assertEquals(2, state.total)
        assertTrue(state.canValidate)
        assertFalse(state.alwaysForMerchant)
    }

    @Test
    fun `a line without suggestion cannot be validated`() = runTest {
        val viewModel = load()
        advanceUntilIdle()

        viewModel.select("tx-2")

        assertEquals("tx-2", viewModel.uiState.value.current?.id)
        assertFalse(viewModel.uiState.value.canValidate)
    }

    @Test
    fun `validating files the line under its suggestion and moves on to the next`() = runTest {
        coEvery { transactions.sortToCategory("tx-1", "cat-courses", false) } returns
            Result.success(SortResult(success = true, remaining = 1))
        coEvery { transactions.getToSort() } returnsMany listOf(
            Result.success(ToSortPile(2, listOf(carrefour, unknown))),
            Result.success(ToSortPile(1, listOf(unknown))),
        )
        val viewModel = load()
        advanceUntilIdle()

        viewModel.validate()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("tx-2", state.current?.id)
        assertEquals(1, state.total)
        assertFalse(state.isSorting)
        assertNull(state.sortError)
        coVerify(exactly = 1) { transactions.sortToCategory("tx-1", "cat-courses", false) }
    }

    @Test
    fun `the last answer leaves an empty pile`() = runTest {
        coEvery { transactions.getToSort() } returnsMany listOf(
            Result.success(ToSortPile(1, listOf(carrefour))),
            Result.success(ToSortPile(0, emptyList())),
        )
        coEvery { transactions.sortToCategory(any(), any(), any()) } returns Result.success(SortResult(success = true, remaining = 0))
        val viewModel = load()
        advanceUntilIdle()

        viewModel.validate()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isEmpty)
        assertNull(viewModel.uiState.value.current)
    }

    @Test
    fun `an empty pile is said once it was read, not while loading`() = runTest {
        coEvery { transactions.getToSort() } returns Result.success(ToSortPile(0, emptyList()))
        val viewModel = load()

        assertFalse(viewModel.uiState.value.isEmpty)

        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isEmpty)
    }

    @Test
    fun `a pile that cannot be read is an error to retry, not an empty pile`() = runTest {
        coEvery { transactions.getToSort() } returns Result.failure(RuntimeException("boom"))
        val viewModel = load()
        advanceUntilIdle()

        assertEquals("boom", viewModel.uiState.value.loadError)
        assertFalse(viewModel.uiState.value.isEmpty)
        assertFalse(viewModel.uiState.value.loaded)

        coEvery { transactions.getToSort() } returns Result.success(ToSortPile(1, listOf(carrefour)))
        viewModel.refresh()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.loadError)
        assertEquals("tx-1", viewModel.uiState.value.current?.id)
    }

    @Test
    fun `a refused answer keeps the card where it was and says so`() = runTest {
        coEvery { transactions.sortToCategory(any(), any(), any()) } returns Result.failure(RuntimeException("already sorted"))
        val viewModel = load()
        advanceUntilIdle()

        viewModel.validate()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("tx-1", state.current?.id)
        assertEquals("already sorted", state.sortError)
        assertFalse(state.isSorting)

        viewModel.clearSortError()

        assertNull(viewModel.uiState.value.sortError)
    }

    @Test
    fun `another category is sent as chosen, with the suggestion left alone`() = runTest {
        coEvery { transactions.sortToCategory("tx-2", "cat-food", false) } returns Result.success(SortResult(success = true, remaining = 1))
        val viewModel = load()
        advanceUntilIdle()
        viewModel.select("tx-2")

        viewModel.openCategorySearch()
        assertTrue(viewModel.uiState.value.choosingCategory)
        viewModel.chooseCategory("cat-food")
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.choosingCategory)
        coVerify(exactly = 1) { transactions.sortToCategory("tx-2", "cat-food", false) }
    }

    @Test
    fun `closing the search without a pick answers nothing`() = runTest {
        val viewModel = load()
        advanceUntilIdle()

        viewModel.openCategorySearch()
        viewModel.closeCategorySearch()

        assertFalse(viewModel.uiState.value.choosingCategory)
        coVerify(exactly = 0) { transactions.sortToCategory(any(), any(), any()) }
    }

    @Test
    fun `always for this merchant is sent with the answer, tells what the rule did and falls back to off`() = runTest {
        coEvery { transactions.sortToCategory("tx-1", "cat-courses", true) } returns Result.success(
            SortResult(
                success = true,
                rule = SortedRule("rule-1", "CARREFOUR"),
                ruleCreated = true,
                alsoSorted = 2,
                remaining = 0,
            ),
        )
        val viewModel = load()
        advanceUntilIdle()

        viewModel.setAlwaysForMerchant(true)
        assertTrue(viewModel.uiState.value.alwaysForMerchant)
        viewModel.validate()
        advanceUntilIdle()

        assertEquals("Règle « CARREFOUR » créée · 2 autres lignes classées", viewModel.uiState.value.message)
        assertFalse(viewModel.uiState.value.alwaysForMerchant)
        coVerify(exactly = 1) { transactions.sortToCategory("tx-1", "cat-courses", true) }
    }

    @Test
    fun `a line a rule filed meanwhile is gone from the pile once it is read again`() = runTest {
        coEvery { transactions.sortToCategory(any(), any(), any()) } returns Result.success(SortResult(success = true, remaining = 0))
        coEvery { transactions.getToSort() } returnsMany listOf(
            Result.success(ToSortPile(2, listOf(carrefour, unknown))),
            Result.success(ToSortPile(0, emptyList())),
        )
        val viewModel = load()
        advanceUntilIdle()

        viewModel.validate()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.pile.isEmpty())
        coVerify(exactly = 2) { transactions.getToSort() }
    }

    @Test
    fun `an internal transfer lists the other legs, then marks the line with the one chosen`() = runTest {
        val leg = TransferLeg(id = "leg-1", label = "VIR LIVRET", amountCents = 5320, accountName = "Livret")
        coEvery { transactions.getTransferCandidates("tx-1") } returns Result.success(listOf(leg))
        coEvery { transactions.sortAsInternalTransfer("tx-1", "leg-1") } returns Result.success(SortResult(success = true, remaining = 1))
        val viewModel = load()
        advanceUntilIdle()

        viewModel.openTransfer()
        advanceUntilIdle()

        assertEquals(listOf(leg), viewModel.uiState.value.transfer?.candidates)
        assertFalse(viewModel.uiState.value.transfer!!.isLoading)

        viewModel.markAsInternalTransfer("leg-1")
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.transfer)
        coVerify(exactly = 1) { transactions.sortAsInternalTransfer("tx-1", "leg-1") }
    }

    @Test
    fun `an internal transfer can be answered without a counterpart, even when no candidate could be read`() = runTest {
        coEvery { transactions.getTransferCandidates("tx-1") } returns Result.failure(RuntimeException("boom"))
        coEvery { transactions.sortAsInternalTransfer("tx-1", null) } returns Result.success(SortResult(success = true, remaining = 1))
        val viewModel = load()
        advanceUntilIdle()

        viewModel.openTransfer()
        advanceUntilIdle()

        assertNotNull(viewModel.uiState.value.transfer)
        assertTrue(viewModel.uiState.value.transfer!!.candidates.isEmpty())

        viewModel.markAsInternalTransfer(null)
        advanceUntilIdle()

        coVerify(exactly = 1) { transactions.sortAsInternalTransfer("tx-1", null) }
    }

    @Test
    fun `a second tap while an answer is on its way sends nothing more`() = runTest {
        coEvery { transactions.sortToCategory(any(), any(), any()) } returns Result.success(SortResult(success = true, remaining = 1))
        val viewModel = load()
        advanceUntilIdle()

        viewModel.validate()
        viewModel.validate()
        advanceUntilIdle()

        coVerify(exactly = 1) { transactions.sortToCategory(any(), any(), any()) }
    }
}

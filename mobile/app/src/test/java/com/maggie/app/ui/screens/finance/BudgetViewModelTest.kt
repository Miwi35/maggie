package com.maggie.app.ui.screens.finance

import com.maggie.app.data.api.EnvelopeCreateRequest
import com.maggie.app.data.model.BudgetLine
import com.maggie.app.data.model.BudgetStatus
import com.maggie.app.data.model.Category
import com.maggie.app.data.model.Envelope
import com.maggie.app.data.repository.BudgetRepository
import com.maggie.app.data.repository.CategoryRepository
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class BudgetViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var budgetRepository: BudgetRepository
    private lateinit var categoryRepository: CategoryRepository
    private lateinit var viewModel: BudgetViewModel

    private val today = LocalDate.of(2026, 7, 15)

    private val sampleStatus = BudgetStatus(
        year = 2026,
        month = 7,
        totalBudgetedCents = 160000,
        totalSpentCents = 5799,
        totalRemainingCents = 154201,
        budgets = listOf(
            BudgetLine(
                id = "env-1",
                categoryId = "cat-1",
                categoryName = "Alimentation",
                mode = "monthly",
                amountCents = 40000,
                year = 2026,
                month = 7,
                spentCents = 4599,
                remainingCents = 35401,
            ),
        ),
    )

    private val sampleCategories = listOf(
        Category(id = "cat-2", name = "Loisirs", obligation = "optional"),
        Category(id = "cat-1", name = "Alimentation", obligation = "mandatory"),
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        budgetRepository = mockk()
        categoryRepository = mockk()
        coEvery { budgetRepository.getBudgetStatus(any(), any()) } returns Result.success(sampleStatus)
        coEvery { categoryRepository.getCategories() } returns Result.success(sampleCategories)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun buildViewModel() = BudgetViewModel(budgetRepository, categoryRepository, today)

    @Test
    fun `initial load fetches the current period and its categories`() = runTest {
        viewModel = buildViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(2026, state.year)
        assertEquals(7, state.month)
        assertEquals(sampleStatus, state.status)
        assertEquals("Alimentation", state.categories[0].name)
        assertFalse(state.isLoading)
        assertNull(state.error)

        coVerify { budgetRepository.getBudgetStatus(2026, 7) }
    }

    @Test
    fun `shiftPeriod moves to the previous month and reloads`() = runTest {
        viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.shiftPeriod(-1)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(2026, state.year)
        assertEquals(6, state.month)
        coVerify { budgetRepository.getBudgetStatus(2026, 6) }
    }

    @Test
    fun `shiftPeriod rolls over to the next year in December`() = runTest {
        viewModel = BudgetViewModel(budgetRepository, categoryRepository, LocalDate.of(2026, 12, 1))
        advanceUntilIdle()

        viewModel.shiftPeriod(1)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(2027, state.year)
        assertEquals(1, state.month)
    }

    @Test
    fun `createEnvelope dispatches the request and reloads the status`() = runTest {
        coEvery { budgetRepository.createEnvelope(any()) } returns Result.success(
            Envelope(id = "env-2", amountCents = 15000, year = 2026, month = 7),
        )
        viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.createEnvelope(
            EnvelopeCreateRequest(
                category = "/api/categories/cat-2",
                amountCents = 15000,
                year = 2026,
                month = 7,
            ),
        )
        advanceUntilIdle()

        coVerify { budgetRepository.createEnvelope(any()) }
        coVerify(atLeast = 2) { budgetRepository.getBudgetStatus(2026, 7) }
    }

    @Test
    fun `deleteEnvelope reloads the status`() = runTest {
        coEvery { budgetRepository.deleteEnvelope("env-1") } returns Result.success(Unit)
        viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.deleteEnvelope("env-1")
        advanceUntilIdle()

        coVerify { budgetRepository.deleteEnvelope("env-1") }
        coVerify(atLeast = 2) { budgetRepository.getBudgetStatus(2026, 7) }
    }

    @Test
    fun `load failure sets error`() = runTest {
        coEvery { budgetRepository.getBudgetStatus(any(), any()) } returns
            Result.failure(RuntimeException("boom"))

        viewModel = buildViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("boom", state.error)
        assertNull(state.status)
        assertTrue(state.categories.isNotEmpty())
    }
}

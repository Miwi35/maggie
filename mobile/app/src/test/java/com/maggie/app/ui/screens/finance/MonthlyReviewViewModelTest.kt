package com.maggie.app.ui.screens.finance

import com.maggie.app.data.model.MonthlyReview
import com.maggie.app.data.model.PendingSpend
import com.maggie.app.data.model.ReviewComparison
import com.maggie.app.data.repository.MonthlyReviewRepository
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
class MonthlyReviewViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var reviewRepository: MonthlyReviewRepository
    private lateinit var viewModel: MonthlyReviewViewModel

    private val today = LocalDate.of(2026, 9, 15)

    private val review = MonthlyReview(
        year = 2026,
        month = 8,
        keptCents = 12000,
        avoidableCents = 3000,
        pendingCount = 1,
        optimisationScore = 80,
        pending = listOf(
            PendingSpend(id = "tx-1", label = "Achat divers", amountCents = -5000),
        ),
        comparison = ReviewComparison(thisMonthCents = 110000, previousMonthCents = 60000),
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        reviewRepository = mockk()
        coEvery { reviewRepository.getReview(any(), any()) } returns Result.success(review)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun buildViewModel() = MonthlyReviewViewModel(reviewRepository, today)

    @Test
    fun `a review opens on the month just ended`() = runTest {
        viewModel = buildViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(2026, state.year)
        assertEquals(8, state.month)
        assertEquals(80, state.review?.optimisationScore)
        assertFalse(state.isLoading)
        assertNull(state.error)

        coVerify { reviewRepository.getReview(2026, 8) }
    }

    @Test
    fun `shiftPeriod walks back through the months`() = runTest {
        viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.shiftPeriod(-1)
        advanceUntilIdle()

        assertEquals(7, viewModel.uiState.value.month)
        coVerify { reviewRepository.getReview(2026, 7) }
    }

    @Test
    fun `rating a spend reloads the review`() = runTest {
        coEvery { reviewRepository.rate(any(), any()) } returns Result.success(Unit)

        viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.rate("tx-1", "avoidable")
        advanceUntilIdle()

        coVerify { reviewRepository.rate("tx-1", "avoidable") }
        coVerify(atLeast = 2) { reviewRepository.getReview(2026, 8) }
    }

    @Test
    fun `a refused verdict surfaces the error`() = runTest {
        coEvery { reviewRepository.rate(any(), any()) } returns Result.failure(RuntimeException("boom"))

        viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.rate("tx-1", "keep")
        advanceUntilIdle()

        assertEquals("boom", viewModel.uiState.value.error)
    }

    @Test
    fun `load failure sets error`() = runTest {
        coEvery { reviewRepository.getReview(any(), any()) } returns Result.failure(RuntimeException("boom"))

        viewModel = buildViewModel()
        advanceUntilIdle()

        assertEquals("boom", viewModel.uiState.value.error)
        assertNull(viewModel.uiState.value.review)
    }
}

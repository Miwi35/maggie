package com.maggie.app.ui.screens.finance

import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.model.ToSortPile
import com.maggie.app.data.repository.TransactionRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ToQualifyViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var transactions: TransactionRepository
    private lateinit var mercure: MercureService
    private lateinit var auth: AuthRepository

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        transactions = mockk()
        mercure = mockk()
        auth = mockk()
        every { mercure.subscribe(any()) } returns emptyFlow()
        coEvery { auth.getUserId() } returns "user-1"
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `the pile is unknown until it is read, then it is the size of the pile and its weight`() = runTest {
        coEvery { transactions.getToSort(limit = 1) } returns Result.success(ToSortPile(total = 3, totalCents = 6520))
        val viewModel = ToQualifyViewModel(transactions, mercure, auth)

        assertNull(viewModel.pile.value)

        advanceUntilIdle()

        assertEquals(3, viewModel.pile.value?.total)
        assertEquals(6520, viewModel.pile.value?.totalCents)
    }

    @Test
    fun `a read that fails leaves the pill as it was`() = runTest {
        coEvery { transactions.getToSort(limit = 1) } returnsMany listOf(
            Result.success(ToSortPile(total = 2, totalCents = 100)),
            Result.failure(RuntimeException("boom")),
        )
        val viewModel = ToQualifyViewModel(transactions, mercure, auth)
        advanceUntilIdle()

        viewModel.refresh()
        advanceUntilIdle()

        assertEquals(2, viewModel.pile.value?.total)
        coVerify(exactly = 2) { transactions.getToSort(limit = 1) }
    }

    @Test
    fun `a read that fails at first draws nothing`() = runTest {
        coEvery { transactions.getToSort(limit = 1) } returns Result.failure(RuntimeException("boom"))
        val viewModel = ToQualifyViewModel(transactions, mercure, auth)
        advanceUntilIdle()

        assertNull(viewModel.pile.value)
    }
}

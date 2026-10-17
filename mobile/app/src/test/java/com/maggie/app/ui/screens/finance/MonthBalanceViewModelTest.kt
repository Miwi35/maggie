package com.maggie.app.ui.screens.finance

import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureEvent
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.model.BalanceEnvelope
import com.maggie.app.data.model.MonthBalance
import com.maggie.app.data.repository.MonthBalanceRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
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
class MonthBalanceViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var repository: MonthBalanceRepository
    private lateinit var mercureService: MercureService
    private lateinit var authRepository: AuthRepository
    private val topics = mutableListOf<String>()
    private val mercureEvents = MutableSharedFlow<MercureEvent>(extraBufferCapacity = 8)

    private val today = LocalDate.of(2026, 9, 17)

    private val september = MonthBalance(
        month = "2026-09",
        isCurrentMonth = true,
        envelopes = listOf(BalanceEnvelope(envelopeId = "e1", name = "Transport", budgetCents = 10000, consumedCents = 15000, percent = 150, status = "over")),
    )
    private val august = MonthBalance(month = "2026-08")

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        repository = mockk()
        coEvery { repository.getBalance(2026, 9) } returns Result.success(september)
        coEvery { repository.getBalance(2026, 8) } returns Result.success(august)
        mercureService = mockk()
        authRepository = mockk()
        coEvery { authRepository.getUserId() } returns "user-1"
        every { mercureService.subscribe(capture(topics)) } returns mercureEvents
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun buildViewModel() = MonthBalanceViewModel(repository, mercureService, authRepository, today)

    @Test
    fun `it opens on the current month and loads its balance`() = runTest {
        val viewModel = buildViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(2026, state.year)
        assertEquals(9, state.month)
        assertEquals(september, state.balance)
        assertFalse(state.isLoading)
        assertNull(state.error)
        coVerify { repository.getBalance(2026, 9) }
    }

    @Test
    fun `it is loading until the answer comes`() = runTest {
        val answer = CompletableDeferred<Result<MonthBalance>>()
        coEvery { repository.getBalance(any(), any()) } coAnswers { answer.await() }

        val viewModel = buildViewModel()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isLoading)

        answer.complete(Result.success(september))
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isLoading)
        assertEquals(september, viewModel.uiState.value.balance)
    }

    @Test
    fun `shifting the period loads the other month and drops the figures of the one left`() = runTest {
        val viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.shiftPeriod(-1)
        assertNull(viewModel.uiState.value.balance)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(8, state.month)
        assertEquals(august, state.balance)
        coVerify { repository.getBalance(2026, 8) }
    }

    @Test
    fun `shifting past january goes to the previous year`() = runTest {
        coEvery { repository.getBalance(any(), any()) } returns Result.success(august)
        val viewModel = MonthBalanceViewModel(repository, mercureService, authRepository, LocalDate.of(2026, 1, 10))
        advanceUntilIdle()

        viewModel.shiftPeriod(-1)
        advanceUntilIdle()

        assertEquals(2025, viewModel.uiState.value.year)
        assertEquals(12, viewModel.uiState.value.month)
        coVerify { repository.getBalance(2025, 12) }
    }

    @Test
    fun `an answer for the month left does not overwrite the month shown`() = runTest {
        val slowSeptember = CompletableDeferred<Result<MonthBalance>>()
        coEvery { repository.getBalance(2026, 9) } coAnswers { slowSeptember.await() }

        val viewModel = buildViewModel()
        advanceUntilIdle()
        viewModel.shiftPeriod(-1)
        advanceUntilIdle()

        slowSeptember.complete(Result.success(september))
        advanceUntilIdle()

        assertEquals(august, viewModel.uiState.value.balance)
    }

    @Test
    fun `a failure sets the error, keeps no balance, and the retry loads it`() = runTest {
        coEvery { repository.getBalance(any(), any()) } returns Result.failure(RuntimeException("boom"))
        val viewModel = buildViewModel()
        advanceUntilIdle()

        assertEquals("boom", viewModel.uiState.value.error)
        assertNull(viewModel.uiState.value.balance)

        coEvery { repository.getBalance(any(), any()) } returns Result.success(september)
        viewModel.refresh()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.error)
        assertEquals(september, viewModel.uiState.value.balance)
    }

    @Test
    fun `clearing the error empties it`() = runTest {
        coEvery { repository.getBalance(any(), any()) } returns Result.failure(RuntimeException("boom"))
        val viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.clearError()

        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `it listens to the transactions and the envelopes of the user, and reloads when they move`() = runTest {
        val viewModel = buildViewModel()
        advanceUntilIdle()

        assertEquals(2, topics.size)
        assertTrue(topics.all { it.contains("user-1") })

        coEvery { repository.getBalance(2026, 9) } returns Result.success(september.copy(isCurrentMonth = false))
        mercureEvents.tryEmit(MercureEvent(data = "{}"))
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.balance!!.isCurrentMonth)
    }
}

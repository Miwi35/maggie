package com.maggie.app.ui.screens.finance

import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureEvent
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.repository.UpcomingRepository
import com.maggie.app.screentest.upcomingFixture
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
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UpcomingViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var repository: UpcomingRepository
    private lateinit var mercureService: MercureService
    private lateinit var authRepository: AuthRepository
    private val topics = mutableListOf<String>()
    private val mercureEvents = MutableSharedFlow<MercureEvent>(extraBufferCapacity = 8)

    private val projection = upcomingFixture()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        repository = mockk()
        coEvery { repository.getUpcoming() } returns Result.success(projection)
        mercureService = mockk()
        authRepository = mockk()
        coEvery { authRepository.getUserId() } returns "user-1"
        every { mercureService.subscribe(capture(topics)) } returns mercureEvents
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun buildViewModel() = UpcomingViewModel(repository, mercureService, authRepository)

    @Test
    fun `it loads on creation, loading then loaded`() = runTest {
        val gate = CompletableDeferred<Unit>()
        coEvery { repository.getUpcoming() } coAnswers {
            gate.await()
            Result.success(projection)
        }

        val viewModel = buildViewModel()
        runCurrent()
        assertTrue(viewModel.uiState.value.isLoading)
        assertNull(viewModel.uiState.value.projection)

        gate.complete(Unit)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertNull(state.error)
        assertEquals(98800, state.projection?.month?.remainingCents)
    }

    @Test
    fun `a failed read says why, and a retry loads the projection`() = runTest {
        coEvery { repository.getUpcoming() } returns Result.failure(IllegalStateException("Service indisponible"))
        val viewModel = buildViewModel()
        advanceUntilIdle()

        assertEquals("Service indisponible", viewModel.uiState.value.error)
        assertNull(viewModel.uiState.value.projection)
        assertFalse(viewModel.uiState.value.isLoading)

        coEvery { repository.getUpcoming() } returns Result.success(projection)
        viewModel.refresh()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.error)
        assertEquals(projection, viewModel.uiState.value.projection)
    }

    @Test
    fun `a failed refresh keeps the figures already read`() = runTest {
        val viewModel = buildViewModel()
        advanceUntilIdle()

        coEvery { repository.getUpcoming() } returns Result.failure(IllegalStateException("Hors ligne"))
        viewModel.refresh()
        advanceUntilIdle()

        assertEquals("Hors ligne", viewModel.uiState.value.error)
        assertEquals(projection, viewModel.uiState.value.projection)
    }

    @Test
    fun `an envelope adjusted elsewhere, pushed by Mercure, updates what is left toward the projects`() = runTest {
        val viewModel = buildViewModel()
        advanceUntilIdle()
        assertEquals(setOf("/users/user-1/api/envelopes/{id}", "/users/user-1/api/transactions/{id}"), topics.toSet())
        assertEquals(98800, viewModel.uiState.value.projection?.month?.remainingCents)

        val adjusted = projection.copy(month = projection.month.copy(remainingCents = 88800, projectedExpenseCents = 161200))
        coEvery { repository.getUpcoming() } returns Result.success(adjusted)
        mercureEvents.tryEmit(MercureEvent(data = "{}"))
        advanceUntilIdle()

        assertEquals(88800, viewModel.uiState.value.projection?.month?.remainingCents)
        coVerify(atLeast = 2) { repository.getUpcoming() }
    }
}

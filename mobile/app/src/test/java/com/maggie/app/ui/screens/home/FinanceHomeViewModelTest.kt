package com.maggie.app.ui.screens.home

import com.maggie.app.data.model.FinanceHomeProject
import com.maggie.app.data.model.FinanceHomeRing
import com.maggie.app.data.model.FinanceHomeState
import com.maggie.app.data.repository.FinanceCardRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The Finance card of the Accueil (MAG-565, MAG-592): read once, read again when a resource behind it moves, one test per state. */
@OptIn(ExperimentalCoroutinesApi::class)
class FinanceHomeViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repository = mockk<FinanceCardRepository>()
    private val changes = MutableSharedFlow<Unit>()

    private fun answer(toQualify: Int, projectName: String = "Voyage") = FinanceHomeState(
        week = FinanceHomeRing(2000, 4000, 7000),
        month = FinanceHomeRing(29000, 9000, 30000),
        toQualifyCount = toQualify,
        nextProject = FinanceHomeProject("p1", projectName, 100000, 25000, "2027-03"),
        accountCount = 2,
        envelopeCount = 4,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { repository.changes() } returns changes
        coEvery { repository.read() } returns Result.success(answer(1))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `the card loads while it reads, then holds the rings and the next project`() = runTest(dispatcher) {
        val viewModel = FinanceHomeViewModel(repository)

        assertTrue(viewModel.uiState.value.isLoading)
        assertNull(viewModel.uiState.value.state)

        advanceUntilIdle()

        val ui = viewModel.uiState.value
        assertFalse(ui.isLoading)
        assertFalse(ui.failed)
        assertEquals(2000, ui.state?.week?.spentCents)
        assertEquals(29000, ui.state?.month?.spentCents)
        assertEquals(1, ui.state?.toQualifyCount)
        assertEquals("Voyage", ui.state?.nextProject?.name)
    }

    @Test
    fun `a failing read shows the failure, with nothing to draw`() = runTest(dispatcher) {
        coEvery { repository.read() } returns Result.failure(RuntimeException("503"))
        val viewModel = FinanceHomeViewModel(repository)

        advanceUntilIdle()

        val ui = viewModel.uiState.value
        assertTrue(ui.failed)
        assertFalse(ui.isLoading)
        assertNull(ui.state)
    }

    @Test
    fun `retrying after a failure reads again and clears it`() = runTest(dispatcher) {
        coEvery { repository.read() } returns Result.failure(RuntimeException("503"))
        val viewModel = FinanceHomeViewModel(repository)
        advanceUntilIdle()

        coEvery { repository.read() } returns Result.success(answer(1))
        viewModel.refresh()
        assertTrue(viewModel.uiState.value.isLoading)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.failed)
        assertNotNull(viewModel.uiState.value.state)
    }

    @Test
    fun `a change announced by the API reads the card again`() = runTest(dispatcher) {
        val viewModel = FinanceHomeViewModel(repository)
        advanceUntilIdle()

        coEvery { repository.read() } returns Result.success(answer(2, projectName = "Piano"))
        changes.emit(Unit)
        advanceUntilIdle()

        assertEquals("Piano", viewModel.uiState.value.state?.nextProject?.name)
        coVerify(exactly = 2) { repository.read() }
    }

    @Test
    fun `a read that fails after a good one keeps the card and flags the failure`() = runTest(dispatcher) {
        val viewModel = FinanceHomeViewModel(repository)
        advanceUntilIdle()

        coEvery { repository.read() } returns Result.failure(RuntimeException("offline"))
        changes.emit(Unit)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.failed)
        assertEquals("Voyage", viewModel.uiState.value.state?.nextProject?.name)
    }

    private val noAccountNorEnvelope = FinanceHomeState()

    @Test
    fun `status - loading, then filled, empty, filled again on a push, failed, loading on retry`() = runTest(dispatcher) {
        val viewModel = FinanceHomeViewModel(repository)
        assertEquals(HomeCardStatus.LOADING, viewModel.uiState.value.status)

        advanceUntilIdle()
        assertEquals(HomeCardStatus.FILLED, viewModel.uiState.value.status)

        coEvery { repository.read() } returns Result.success(noAccountNorEnvelope)
        changes.emit(Unit)
        advanceUntilIdle()
        assertEquals(HomeCardStatus.EMPTY, viewModel.uiState.value.status)

        coEvery { repository.read() } returns Result.success(answer(1))
        changes.emit(Unit)
        advanceUntilIdle()
        assertEquals(HomeCardStatus.FILLED, viewModel.uiState.value.status)

        coEvery { repository.read() } returns Result.failure(RuntimeException("offline"))
        changes.emit(Unit)
        advanceUntilIdle()
        assertEquals(HomeCardStatus.ERROR, viewModel.uiState.value.status)

        coEvery { repository.read() } returns Result.success(noAccountNorEnvelope)
        viewModel.refresh()
        assertEquals(HomeCardStatus.LOADING, viewModel.uiState.value.status)
        advanceUntilIdle()
        assertEquals(HomeCardStatus.EMPTY, viewModel.uiState.value.status)
    }
}

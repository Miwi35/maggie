package com.maggie.app.ui.screens.home

import com.maggie.app.data.model.HomeDay
import com.maggie.app.data.repository.HomeCardRepository
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The Finance card of the Accueil (MAG-565): read once, read again when the API says it moved, one test per state. */
@OptIn(ExperimentalCoroutinesApi::class)
class FinanceHomeViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repository = mockk<HomeCardRepository>()
    private val changes = MutableSharedFlow<Unit>()

    private fun answer(toQualify: Int, projectName: String = "Voyage"): JsonObject = Json.parseToJsonElement(
        """
        {"week":{"status":"on_track","label":"Dans le rythme","budgetCents":7000,"remainingCents":5000,"expectedRemainingCents":4000},
         "month":{"status":"above","label":"Au-dessus du rythme","budgetCents":30000,"remainingCents":1000,"expectedRemainingCents":9000},
         "toQualifyCount":$toQualify,"accountCount":2,"envelopeCount":4,
         "nextProject":{"projectId":"p1","name":"$projectName","amountCents":100000,"gatheredCents":25000,"purchaseMonth":"2027-03"},
         "extra":"ignored"}
        """.trimIndent(),
    ) as JsonObject

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { repository.changes("finance") } returns changes
        coEvery { repository.state("finance", HomeDay.TODAY) } returns Result.success(answer(1))
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
        assertEquals("on_track", ui.state?.week?.status)
        assertEquals("above", ui.state?.month?.status)
        assertEquals(1, ui.state?.toQualifyCount)
        assertEquals("Voyage", ui.state?.nextProject?.name)
    }

    @Test
    fun `a failing read shows the failure, with nothing to draw`() = runTest(dispatcher) {
        coEvery { repository.state(any(), any()) } returns Result.failure(RuntimeException("503"))
        val viewModel = FinanceHomeViewModel(repository)

        advanceUntilIdle()

        val ui = viewModel.uiState.value
        assertTrue(ui.failed)
        assertFalse(ui.isLoading)
        assertNull(ui.state)
    }

    @Test
    fun `retrying after a failure reads again and clears it`() = runTest(dispatcher) {
        coEvery { repository.state(any(), any()) } returns Result.failure(RuntimeException("503"))
        val viewModel = FinanceHomeViewModel(repository)
        advanceUntilIdle()

        coEvery { repository.state("finance", HomeDay.TODAY) } returns Result.success(answer(1))
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

        coEvery { repository.state("finance", HomeDay.TODAY) } returns Result.success(answer(2, projectName = "Piano"))
        changes.emit(Unit)
        advanceUntilIdle()

        assertEquals("Piano", viewModel.uiState.value.state?.nextProject?.name)
        coVerify(exactly = 2) { repository.state("finance", HomeDay.TODAY) }
    }

    @Test
    fun `a read that fails after a good one keeps the card and flags the failure`() = runTest(dispatcher) {
        val viewModel = FinanceHomeViewModel(repository)
        advanceUntilIdle()

        coEvery { repository.state(any(), any()) } returns Result.failure(RuntimeException("offline"))
        changes.emit(Unit)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.failed)
        assertEquals("Voyage", viewModel.uiState.value.state?.nextProject?.name)
    }

    @Test
    fun `the card is read for today whatever the selector says, the card does not follow Demain`() = runTest(dispatcher) {
        FinanceHomeViewModel(repository)
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.state(any(), HomeDay.TOMORROW) }
    }

    private val noAccountNorEnvelope = Json.parseToJsonElement(
        """{"week":{"status":"on_track","label":"","budgetCents":0,"remainingCents":0,"expectedRemainingCents":0},
           "month":{"status":"on_track","label":"","budgetCents":0,"remainingCents":0,"expectedRemainingCents":0},
           "toQualifyCount":0,"nextProject":null,"accountCount":0,"envelopeCount":0}""",
    ) as JsonObject

    @Test
    fun `status - loading, then filled, empty, filled again on a push, failed, loading on retry`() = runTest(dispatcher) {
        val viewModel = FinanceHomeViewModel(repository)
        assertEquals(HomeCardStatus.LOADING, viewModel.uiState.value.status)

        advanceUntilIdle()
        assertEquals(HomeCardStatus.FILLED, viewModel.uiState.value.status)

        coEvery { repository.state("finance", HomeDay.TODAY) } returns Result.success(noAccountNorEnvelope)
        changes.emit(Unit)
        advanceUntilIdle()
        assertEquals(HomeCardStatus.EMPTY, viewModel.uiState.value.status)

        coEvery { repository.state("finance", HomeDay.TODAY) } returns Result.success(answer(1))
        changes.emit(Unit)
        advanceUntilIdle()
        assertEquals(HomeCardStatus.FILLED, viewModel.uiState.value.status)

        coEvery { repository.state(any(), any()) } returns Result.failure(RuntimeException("offline"))
        changes.emit(Unit)
        advanceUntilIdle()
        assertEquals(HomeCardStatus.ERROR, viewModel.uiState.value.status)

        coEvery { repository.state("finance", HomeDay.TODAY) } returns Result.success(noAccountNorEnvelope)
        viewModel.refresh()
        assertEquals(HomeCardStatus.LOADING, viewModel.uiState.value.status)
        advanceUntilIdle()
        assertEquals(HomeCardStatus.EMPTY, viewModel.uiState.value.status)
    }
}

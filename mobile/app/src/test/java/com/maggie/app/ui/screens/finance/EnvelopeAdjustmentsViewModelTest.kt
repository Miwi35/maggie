package com.maggie.app.ui.screens.finance

import com.maggie.app.data.model.AdjustmentLine
import com.maggie.app.data.model.AdjustmentSeason
import com.maggie.app.data.model.AdjustmentSuggestion
import com.maggie.app.data.model.AppliedAdjustment
import com.maggie.app.data.model.EnvelopeAdjustment
import com.maggie.app.data.model.EnvelopeAdjustments
import com.maggie.app.data.model.EnvelopeAdjustmentsRequest
import com.maggie.app.data.model.EnvelopeAdjustmentsResult
import com.maggie.app.data.repository.BudgetRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
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
import java.time.YearMonth

@OptIn(ExperimentalCoroutinesApi::class)
class EnvelopeAdjustmentsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var budgetRepository: BudgetRepository

    private val month = YearMonth.of(2026, 9)

    // Loisirs over budget three months in a row: a raise. Courses and Cinéma fit, Cinéma has a season.
    private val loisirs = EnvelopeAdjustment(
        envelopeId = "env-loisirs", categoryId = "cat-loisirs", categoryName = "Loisirs", categoryPath = "Loisirs",
        amountCents = 10000, actualCents = 14000, targetEnvelopeId = "env-loisirs-oct", targetAmountCents = 10000,
        suggestion = AdjustmentSuggestion(
            proposedCents = 14000, direction = AdjustmentSuggestion.RAISE,
            reason = "Dépassée trois mois de suite",
        ),
    )
    private val courses = EnvelopeAdjustment(
        envelopeId = "env-courses", categoryId = "cat-courses", categoryName = "Courses", categoryPath = "Courses",
        amountCents = 40000, actualCents = 39000, targetEnvelopeId = "env-courses-oct", targetAmountCents = 40000,
    )
    private val cinema = EnvelopeAdjustment(
        envelopeId = "env-cinema", categoryId = "cat-cinema", categoryName = "Cinéma", categoryPath = "Loisirs › Cinéma",
        amountCents = 2000, actualCents = 4500, targetEnvelopeId = null, targetAmountCents = 2000,
        suggestion = AdjustmentSuggestion(
            proposedCents = 4500, direction = AdjustmentSuggestion.RAISE, reason = "Chaque hiver",
            seasonal = AdjustmentSeason(fromMonth = 10, toMonth = 12),
        ),
    )

    private val read = EnvelopeAdjustments(
        month = "2026-09", targetMonth = "2026-10", suggestionCount = 2,
        envelopes = listOf(courses, loisirs, cinema),
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        budgetRepository = mockk()
        coEvery { budgetRepository.getEnvelopeAdjustments(any()) } returns Result.success(read)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun buildViewModel() = EnvelopeAdjustmentsViewModel(budgetRepository, month)

    private fun applied(vararg lines: AdjustmentLine) = Result.success(
        EnvelopeAdjustmentsResult(
            success = true,
            applied = lines.map { AppliedAdjustment(it.categoryId, "env-${it.categoryId}", it.amountCents) },
        ),
    )

    @Test
    fun `it is loading before the first answer`() = runTest {
        val gate = CompletableDeferred<Unit>()
        coEvery { budgetRepository.getEnvelopeAdjustments(any()) } coAnswers {
            gate.await()
            Result.success(read)
        }

        val viewModel = buildViewModel()
        runCurrent()

        assertTrue(viewModel.uiState.value.isLoading)
        assertNull(viewModel.uiState.value.adjustments)

        gate.complete(Unit)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test
    fun `the month read comes with its envelopes and only the unfit ones carry a suggestion`() = runTest {
        val viewModel = buildViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertNull(state.error)
        assertEquals(3, state.adjustments?.envelopes?.size)
        assertEquals(listOf("cat-loisirs", "cat-cinema"), state.pendingSuggestions.map { it.categoryId })
        assertNull(state.adjustments?.envelopes?.first { it.categoryId == "cat-courses" }?.suggestion)
        assertNotNull(state.adjustments?.envelopes?.first { it.categoryId == "cat-cinema" }?.suggestion?.seasonal)
        coVerify { budgetRepository.getEnvelopeAdjustments("2026-09") }
    }

    @Test
    fun `a failed read shows the error and nothing else`() = runTest {
        coEvery { budgetRepository.getEnvelopeAdjustments(any()) } returns Result.failure(RuntimeException("réseau"))

        val viewModel = buildViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("réseau", state.error)
        assertNull(state.adjustments)
        assertFalse(state.isLoading)
        coVerify(exactly = 0) { budgetRepository.applyEnvelopeAdjustments(any()) }
    }

    @Test
    fun `plus and minus move a draft by a euro and write nothing`() = runTest {
        val viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.step("cat-courses", ADJUSTMENT_STEP_CENTS)
        viewModel.step("cat-courses", ADJUSTMENT_STEP_CENTS)
        viewModel.step("cat-courses", -ADJUSTMENT_STEP_CENTS)

        val state = viewModel.uiState.value
        assertEquals(mapOf("cat-courses" to 40100), state.draft)
        assertEquals(40100, state.amountFor(courses))
        assertTrue(state.hasDraft)
        coVerify(exactly = 0) { budgetRepository.applyEnvelopeAdjustments(any()) }
    }

    @Test
    fun `the slider sets the amount, never below zero, and landing on the stored amount empties the draft`() = runTest {
        val viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.setAmount("cat-courses", 35000)
        assertEquals(35000, viewModel.uiState.value.amountFor(courses))

        viewModel.setAmount("cat-courses", -500)
        assertEquals(0, viewModel.uiState.value.amountFor(courses))

        viewModel.setAmount("cat-courses", 40000)
        assertTrue(viewModel.uiState.value.draft.isEmpty())
    }

    @Test
    fun `apply writes one suggestion as proposed and reads the list again`() = runTest {
        coEvery { budgetRepository.applyEnvelopeAdjustments(any()) } returns
            applied(AdjustmentLine("cat-loisirs", 14000))
        val viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.apply("cat-loisirs")
        advanceUntilIdle()

        coVerify(exactly = 1) {
            budgetRepository.applyEnvelopeAdjustments(
                EnvelopeAdjustmentsRequest("2026-09", listOf(AdjustmentLine("cat-loisirs", 14000, seasonal = false))),
            )
        }
        coVerify(exactly = 2) { budgetRepository.getEnvelopeAdjustments("2026-09") }
        assertEquals("1 enveloppe ajustée", viewModel.uiState.value.message)
        assertFalse(viewModel.uiState.value.isSaving)
    }

    @Test
    fun `applying a seasonal suggestion says it is one`() = runTest {
        coEvery { budgetRepository.applyEnvelopeAdjustments(any()) } returns
            applied(AdjustmentLine("cat-cinema", 4500, seasonal = true))
        val viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.apply("cat-cinema")
        advanceUntilIdle()

        coVerify {
            budgetRepository.applyEnvelopeAdjustments(
                EnvelopeAdjustmentsRequest("2026-09", listOf(AdjustmentLine("cat-cinema", 4500, seasonal = true))),
            )
        }
    }

    @Test
    fun `apply on an envelope with no suggestion writes nothing`() = runTest {
        val viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.apply("cat-courses")
        advanceUntilIdle()

        coVerify(exactly = 0) { budgetRepository.applyEnvelopeAdjustments(any()) }
    }

    @Test
    fun `apply all writes every suggestion in one request and leaves the fitting envelopes alone`() = runTest {
        coEvery { budgetRepository.applyEnvelopeAdjustments(any()) } returns
            applied(AdjustmentLine("cat-loisirs", 14000), AdjustmentLine("cat-cinema", 4500, true))
        val viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.applyAll()
        advanceUntilIdle()

        coVerify(exactly = 1) {
            budgetRepository.applyEnvelopeAdjustments(
                EnvelopeAdjustmentsRequest(
                    "2026-09",
                    listOf(AdjustmentLine("cat-loisirs", 14000, false), AdjustmentLine("cat-cinema", 4500, true)),
                ),
            )
        }
        assertEquals("2 enveloppes ajustées", viewModel.uiState.value.message)
    }

    @Test
    fun `apply all leaves an envelope set by hand to save`() = runTest {
        coEvery { budgetRepository.applyEnvelopeAdjustments(any()) } returns applied(AdjustmentLine("cat-cinema", 4500, true))
        val viewModel = buildViewModel()
        advanceUntilIdle()
        viewModel.step("cat-loisirs", ADJUSTMENT_STEP_CENTS)

        viewModel.applyAll()
        advanceUntilIdle()

        coVerify {
            budgetRepository.applyEnvelopeAdjustments(
                EnvelopeAdjustmentsRequest("2026-09", listOf(AdjustmentLine("cat-cinema", 4500, true))),
            )
        }
        assertEquals(mapOf("cat-loisirs" to 10100), viewModel.uiState.value.draft)
    }

    @Test
    fun `save writes the draft as plain amounts and empties it`() = runTest {
        coEvery { budgetRepository.applyEnvelopeAdjustments(any()) } returns
            applied(AdjustmentLine("cat-courses", 41000), AdjustmentLine("cat-loisirs", 9000))
        val viewModel = buildViewModel()
        advanceUntilIdle()
        viewModel.setAmount("cat-courses", 41000)
        viewModel.setAmount("cat-loisirs", 9000)

        viewModel.save()
        advanceUntilIdle()

        coVerify(exactly = 1) {
            budgetRepository.applyEnvelopeAdjustments(
                EnvelopeAdjustmentsRequest(
                    "2026-09",
                    listOf(AdjustmentLine("cat-courses", 41000, false), AdjustmentLine("cat-loisirs", 9000, false)),
                ),
            )
        }
        assertTrue(viewModel.uiState.value.draft.isEmpty())
        assertEquals("2 enveloppes ajustées", viewModel.uiState.value.message)
    }

    @Test
    fun `save without a draft writes nothing`() = runTest {
        val viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.save()
        advanceUntilIdle()

        coVerify(exactly = 0) { budgetRepository.applyEnvelopeAdjustments(any()) }
    }

    @Test
    fun `a refused write applies nothing, keeps the draft and reports the error`() = runTest {
        coEvery { budgetRepository.applyEnvelopeAdjustments(any()) } returns Result.failure(RuntimeException("refusé"))
        val viewModel = buildViewModel()
        advanceUntilIdle()
        viewModel.setAmount("cat-courses", 41000)

        viewModel.save()
        advanceUntilIdle()
        viewModel.apply("cat-loisirs")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("refusé", state.error)
        assertFalse(state.isSaving)
        assertNull(state.message)
        assertEquals(mapOf("cat-courses" to 41000), state.draft)
        // Neither failure triggered a re-read: what is on screen is still what is stored.
        coVerify(exactly = 1) { budgetRepository.getEnvelopeAdjustments(any()) }
    }

    @Test
    fun `shifting the month reads it and drops the draft of the previous one`() = runTest {
        val viewModel = buildViewModel()
        advanceUntilIdle()
        viewModel.setAmount("cat-courses", 41000)

        viewModel.shiftMonth(-1)
        advanceUntilIdle()

        assertEquals(YearMonth.of(2026, 8), viewModel.uiState.value.month)
        assertTrue(viewModel.uiState.value.draft.isEmpty())
        coVerify { budgetRepository.getEnvelopeAdjustments("2026-08") }
    }
}

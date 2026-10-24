package com.maggie.app.ui.screens.finance

import com.maggie.app.data.api.ApiRefusalException
import com.maggie.app.data.repository.ProjectRepository
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

/** The 20-year frise of the planning card (MAG-554): one test per transition of the horizon and of the comparison. */
@OptIn(ExperimentalCoroutinesApi::class)
class FinanceTimelineViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val repository = mockk<ProjectRepository>()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        coEvery { repository.getTimeline(null) } returns Result.success(TimelineFixtures.alone())
        coEvery { repository.getTimeline("auto-quality") } returns Result.success(TimelineFixtures.compared())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = FinanceTimelineViewModel(repository)

    @Test
    fun `the card starts on 36 months and reads nothing`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(PlanningHorizon.MONTHS_36, state.horizon)
        assertNull(state.timeline)
        assertFalse(state.isLoading)
        coVerify(exactly = 0) { repository.getTimeline(any()) }
    }

    @Test
    fun `choosing 20 ans reads the frise, loading then loaded`() = runTest {
        val viewModel = viewModel()

        viewModel.selectHorizon(PlanningHorizon.YEARS_20)
        assertEquals(PlanningHorizon.YEARS_20, viewModel.uiState.value.horizon)
        assertTrue(viewModel.uiState.value.isLoading)

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertNull(state.error)
        assertEquals("Mon plan", state.timeline?.current?.name)
        assertNull(state.timeline?.compared)
        coVerify(exactly = 1) { repository.getTimeline(null) }
    }

    @Test
    fun `a failed read says so, keeps the horizon, and Réessayer reads again`() = runTest {
        coEvery { repository.getTimeline(null) } returns Result.failure(RuntimeException("Unable to resolve host"))
        val viewModel = viewModel()

        viewModel.selectHorizon(PlanningHorizon.YEARS_20)
        advanceUntilIdle()

        var state = viewModel.uiState.value
        assertEquals(FinanceTimelineViewModel.MESSAGE_LOAD, state.error)
        assertFalse(state.isLoading)
        assertNull(state.timeline)
        assertEquals(PlanningHorizon.YEARS_20, state.horizon)

        coEvery { repository.getTimeline(null) } returns Result.success(TimelineFixtures.alone())
        viewModel.retry()
        advanceUntilIdle()

        state = viewModel.uiState.value
        assertNull(state.error)
        assertNotNull(state.timeline)
    }

    @Test
    fun `choosing a scenario to compare reads the frise again with it`() = runTest {
        val viewModel = viewModel()
        viewModel.selectHorizon(PlanningHorizon.YEARS_20)
        advanceUntilIdle()

        viewModel.selectComparison("auto-quality")
        assertEquals("auto-quality", viewModel.uiState.value.compareId)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("Qualité de vie", state.timeline?.compared?.name)
        assertEquals("N 2 ans 3 mois plus tôt que « Mon plan »", state.timeline?.difference?.sentence)
        coVerify(exactly = 1) { repository.getTimeline("auto-quality") }
    }

    @Test
    fun `dropping the comparison reads the followed scenario alone`() = runTest {
        val viewModel = viewModel()
        viewModel.selectHorizon(PlanningHorizon.YEARS_20)
        viewModel.selectComparison("auto-quality")
        advanceUntilIdle()

        viewModel.selectComparison(null)
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.compareId)
        assertNull(viewModel.uiState.value.timeline?.compared)
    }

    @Test
    fun `a comparison the API refuses falls back to the followed scenario alone, with the API's sentence`() = runTest {
        val refusal = "The current scenario is already drawn: compare it with another one."
        coEvery { repository.getTimeline("auto-rapid") } returns Result.failure(ApiRefusalException(400, refusal))
        val viewModel = viewModel()
        viewModel.selectHorizon(PlanningHorizon.YEARS_20)
        advanceUntilIdle()

        viewModel.selectComparison("auto-rapid")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNull(state.compareId)
        assertEquals(refusal, state.comparisonNotice)
        assertNull(state.error)
        assertEquals("Mon plan", state.timeline?.current?.name)
        assertNull(state.timeline?.compared)
    }

    @Test
    fun `a compared scenario that is gone (404) falls back the same way`() = runTest {
        coEvery { repository.getTimeline("auto-balanced") } returns Result.failure(ApiRefusalException(404, "Scenario not found."))
        val viewModel = viewModel()
        viewModel.selectHorizon(PlanningHorizon.YEARS_20)
        viewModel.selectComparison("auto-balanced")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNull(state.compareId)
        assertEquals("Scenario not found.", state.comparisonNotice)
        assertNotNull(state.timeline)
    }

    @Test
    fun `a network failure while comparing keeps the choice and offers to retry`() = runTest {
        coEvery { repository.getTimeline("auto-quality") } returns Result.failure(RuntimeException("timeout"))
        val viewModel = viewModel()
        viewModel.selectHorizon(PlanningHorizon.YEARS_20)
        advanceUntilIdle()
        viewModel.selectComparison("auto-quality")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("auto-quality", state.compareId)
        assertEquals(FinanceTimelineViewModel.MESSAGE_LOAD, state.error)
        assertNull(state.comparisonNotice)
        // The frise already drawn stays on screen.
        assertNotNull(state.timeline)
    }

    @Test
    fun `choosing a scenario while the card shows 36 months reads nothing until 20 ans`() = runTest {
        val viewModel = viewModel()

        viewModel.selectComparison("auto-quality")
        advanceUntilIdle()
        coVerify(exactly = 0) { repository.getTimeline(any()) }

        viewModel.selectHorizon(PlanningHorizon.YEARS_20)
        advanceUntilIdle()
        coVerify(exactly = 1) { repository.getTimeline("auto-quality") }
    }

    @Test
    fun `back to 36 mois drops a read still on its way`() = runTest {
        val late = CompletableDeferred<Result<com.maggie.app.data.model.FinanceTimeline>>()
        coEvery { repository.getTimeline(null) } coAnswers { late.await() }
        val viewModel = viewModel()

        viewModel.selectHorizon(PlanningHorizon.YEARS_20)
        runCurrent()
        viewModel.selectHorizon(PlanningHorizon.MONTHS_36)
        late.complete(Result.success(TimelineFixtures.alone()))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(PlanningHorizon.MONTHS_36, state.horizon)
        assertFalse(state.isLoading)
        assertNull(state.timeline)
    }

    @Test
    fun `back to 36 mois then 20 ans again reads a fresh frise`() = runTest {
        val viewModel = viewModel()
        viewModel.selectHorizon(PlanningHorizon.YEARS_20)
        advanceUntilIdle()

        viewModel.selectHorizon(PlanningHorizon.MONTHS_36)
        assertEquals(PlanningHorizon.MONTHS_36, viewModel.uiState.value.horizon)
        viewModel.selectHorizon(PlanningHorizon.YEARS_20)
        advanceUntilIdle()

        coVerify(exactly = 2) { repository.getTimeline(null) }
    }

    @Test
    fun `the notice goes once it is read`() = runTest {
        coEvery { repository.getTimeline("auto-rapid") } returns Result.failure(ApiRefusalException(400, "Refused."))
        val viewModel = viewModel()
        viewModel.selectHorizon(PlanningHorizon.YEARS_20)
        viewModel.selectComparison("auto-rapid")
        advanceUntilIdle()
        assertEquals("Refused.", viewModel.uiState.value.comparisonNotice)

        viewModel.dismissNotice()

        assertNull(viewModel.uiState.value.comparisonNotice)
    }
}

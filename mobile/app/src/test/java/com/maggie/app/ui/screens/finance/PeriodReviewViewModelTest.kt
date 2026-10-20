package com.maggie.app.ui.screens.finance

import com.maggie.app.data.model.PeriodBalance
import com.maggie.app.data.model.PeriodPlan
import com.maggie.app.data.model.PlanCategory
import com.maggie.app.data.model.PlanItem
import com.maggie.app.data.model.PlanLastPeriod
import com.maggie.app.data.model.PlanRefusedException
import com.maggie.app.data.model.PlanningPeriodRef
import com.maggie.app.data.model.REVIEW_LEVEL_QUARTER
import com.maggie.app.data.model.REVIEW_LEVEL_YEAR
import com.maggie.app.data.repository.ReviewRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
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

/** The two steps that close a period (MAG-462): the bilan read, the plan edited, applied or refused whole. */
@OptIn(ExperimentalCoroutinesApi::class)
class PeriodReviewViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var review: ReviewRepository

    private val quarter = PlanningPeriodRef("2027-Q1", REVIEW_LEVEL_QUARTER, "2027-01", "2027-03", 3)
    private val year = PlanningPeriodRef("2028", REVIEW_LEVEL_YEAR, "2028-01", "2028-12", 12)

    private fun category(id: String, name: String, envelope: Int? = null, suggested: Int = 0, monthly: Int = 0) =
        PlanCategory(
            categoryId = id,
            categoryName = name,
            lastYear = PlanLastPeriod(consumedCents = suggested),
            envelopeCents = envelope,
            suggestedCents = suggested,
            suggestedMonthlyCents = monthly,
        )

    private fun plan(period: PlanningPeriodRef, vararg categories: PlanCategory) =
        PeriodPlan(period = period, categories = categories.toList())

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        review = mockk()
        coEvery { review.getPeriodBalance("2026-12") } returns Result.success(PeriodBalance())
        coEvery { review.getPeriodPlan("2027-Q1") } returns Result.success(
            plan(
                quarter,
                category("c-loisirs", "Loisirs", suggested = 30_000, monthly = 10_000),
                category("c-courses", "Courses", envelope = 60_000, suggested = 90_000, monthly = 30_000),
                category("c-vide", "Vide"),
            ),
        )
        coEvery { review.applyPeriodPlan(any(), any()) } returns Result.success(Unit)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `the bilan is read for the month the review closes`() = runTest {
        val viewModel = PeriodReviewViewModel(review)

        viewModel.loadBalance("2026-12")
        advanceUntilIdle()

        assertNotNull(viewModel.uiState.value.balance)
        assertNull(viewModel.uiState.value.balanceError)
    }

    @Test
    fun `a bilan that cannot be read says so and a retry reads it again`() = runTest {
        coEvery { review.getPeriodBalance("2026-12") } returns Result.failure(Exception("Hors ligne"))
        val viewModel = PeriodReviewViewModel(review)
        viewModel.loadBalance("2026-12")
        advanceUntilIdle()
        assertEquals("Hors ligne", viewModel.uiState.value.balanceError)
        assertNull(viewModel.uiState.value.balance)

        coEvery { review.getPeriodBalance("2026-12") } returns Result.success(PeriodBalance())
        viewModel.loadBalance("2026-12")
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.balanceError)
        assertNotNull(viewModel.uiState.value.balance)
    }

    @Test
    fun `the plan shows a category set or spent on, and proposes the monthly figure of the period behind`() = runTest {
        val viewModel = PeriodReviewViewModel(review)
        viewModel.loadPlan("2027-Q1")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf("Loisirs", "Courses"), state.rows.map { it.categoryName })
        assertEquals("100", state.amountOf(state.rows[0]))
        assertEquals("200", state.amountOf(state.rows[1]))
    }

    @Test
    fun `only the lines to write are sent - a set envelope left alone is not`() = runTest {
        val viewModel = PeriodReviewViewModel(review)
        viewModel.loadPlan("2027-Q1")
        advanceUntilIdle()

        assertEquals(listOf("Loisirs"), viewModel.uiState.value.lines.map { it.first })
        viewModel.edit("c-courses", "250,5")

        val lines = viewModel.uiState.value.lines
        assertEquals(listOf("Loisirs", "Courses"), lines.map { it.first })
        assertEquals(PlanItem("monthly", 25_050, "EUR", categoryId = "c-courses"), lines[1].second)
    }

    @Test
    fun `a year proposes the amount of the year, as an annual line`() = runTest {
        coEvery { review.getPeriodPlan("2028") } returns Result.success(plan(year, category("c-loisirs", "Loisirs", suggested = 120_000, monthly = 10_000)))
        val viewModel = PeriodReviewViewModel(review)
        viewModel.loadPlan("2028")
        advanceUntilIdle()

        val line = viewModel.uiState.value.lines.single().second
        assertEquals("annual", line.kind)
        assertEquals(120_000, line.amountCents)
    }

    @Test
    fun `a one-off event is added with a name and an amount, and removed`() = runTest {
        val viewModel = PeriodReviewViewModel(review)
        viewModel.loadPlan("2027-Q1")
        advanceUntilIdle()

        viewModel.changeDraftName("Escapade")
        assertFalse("an amount is missing", viewModel.uiState.value.canAddOneOff)
        viewModel.changeDraftAmount("450")
        viewModel.addOneOff()

        val state = viewModel.uiState.value
        assertEquals("", state.draftName)
        assertEquals(PlanItem("one_off", 45_000, "EUR", name = "Escapade"), state.lines.last().second)

        viewModel.removeOneOff(0)
        assertTrue(viewModel.uiState.value.oneOffs.isEmpty())
    }

    @Test
    fun `Appliquer le plan sends every line in one call and confirms`() = runTest {
        val sent = slot<List<PlanItem>>()
        coEvery { review.applyPeriodPlan("2027-Q1", capture(sent)) } returns Result.success(Unit)
        val viewModel = PeriodReviewViewModel(review)
        viewModel.loadPlan("2027-Q1")
        advanceUntilIdle()
        viewModel.changeDraftName("Escapade")
        viewModel.changeDraftAmount("450")
        viewModel.addOneOff()

        viewModel.apply()
        advanceUntilIdle()

        coVerify(exactly = 1) { review.applyPeriodPlan("2027-Q1", any()) }
        assertEquals(listOf("monthly", "one_off"), sent.captured.map { it.kind })
        assertTrue(viewModel.uiState.value.applied)
        assertNull(viewModel.uiState.value.refusal)
        assertFalse(viewModel.uiState.value.applying)
    }

    @Test
    fun `a plan refused on a line names it, says nothing was written and keeps what was typed`() = runTest {
        coEvery { review.applyPeriodPlan(any(), any()) } returns Result.failure(PlanRefusedException("Montant invalide.", 1))
        val viewModel = PeriodReviewViewModel(review)
        viewModel.loadPlan("2027-Q1")
        advanceUntilIdle()
        viewModel.edit("c-courses", "250")

        viewModel.apply()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.applied)
        assertEquals("Courses", state.refusal?.line)
        assertEquals("Montant invalide.", state.refusal?.message)
        assertEquals("250", state.edits["c-courses"])
        assertFalse(state.applying)
    }

    @Test
    fun `with nothing to write the plan is not sent`() = runTest {
        coEvery { review.getPeriodPlan("2027-Q1") } returns Result.success(plan(quarter, category("c-vide", "Vide")))
        val viewModel = PeriodReviewViewModel(review)
        viewModel.loadPlan("2027-Q1")
        advanceUntilIdle()

        viewModel.apply()
        advanceUntilIdle()

        coVerify(exactly = 0) { review.applyPeriodPlan(any(), any()) }
    }

    @Test
    fun `reading the same period again keeps what was typed, another period starts afresh`() = runTest {
        coEvery { review.getPeriodPlan("2027-Q2") } returns Result.success(plan(quarter.copy(key = "2027-Q2")))
        val viewModel = PeriodReviewViewModel(review)
        viewModel.loadPlan("2027-Q1")
        advanceUntilIdle()
        viewModel.edit("c-loisirs", "80")

        viewModel.loadPlan("2027-Q1")
        advanceUntilIdle()
        assertEquals("80", viewModel.uiState.value.edits["c-loisirs"])

        viewModel.loadPlan("2027-Q2")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.edits.isEmpty())
    }

    @Test
    fun `amounts are read with a comma or a point, and only positive ones count`() {
        assertEquals(1250, amountCents("12,5"))
        assertEquals(1250, amountCents("12.5"))
        assertEquals(0, amountCents("-3"))
        assertEquals(0, amountCents("abc"))
        assertEquals("12.5", amountText(1250))
        assertEquals("125", amountText(12_500))
    }
}

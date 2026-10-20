package com.maggie.app.ui.screens.finance

import com.maggie.app.data.model.Category
import com.maggie.app.data.model.ControlResult
import com.maggie.app.data.model.EnvelopeAdjustment
import com.maggie.app.data.model.EnvelopeAdjustments
import com.maggie.app.data.model.Qualification
import com.maggie.app.data.model.QualificationKind
import com.maggie.app.data.model.RETROSPECT_AVOIDABLE
import com.maggie.app.data.model.REVIEW_STEP_BALANCE
import com.maggie.app.data.model.REVIEW_STEP_DONE
import com.maggie.app.data.model.PlanningPeriodRef
import com.maggie.app.data.model.REVIEW_LEVEL_MONTH
import com.maggie.app.data.model.REVIEW_LEVEL_QUARTER
import com.maggie.app.data.model.REVIEW_STEP_ENVELOPES
import com.maggie.app.data.model.REVIEW_STEP_PERIOD_BALANCE
import com.maggie.app.data.model.REVIEW_STEP_PERIOD_PLAN
import com.maggie.app.data.model.REVIEW_STEP_PROJECTS
import com.maggie.app.data.model.REVIEW_STEP_REVIEW
import com.maggie.app.data.model.REVIEW_STEP_UPCOMING
import com.maggie.app.data.model.ReviewProgress
import com.maggie.app.data.model.ReviewQueue
import com.maggie.app.data.model.ReviewQueueItem
import com.maggie.app.data.model.ReviewStep
import com.maggie.app.data.model.SortResult
import com.maggie.app.data.model.SortSuggestion
import com.maggie.app.data.model.Transaction
import com.maggie.app.data.model.UpcomingItem
import com.maggie.app.data.model.UpcomingProjection
import com.maggie.app.data.model.UpcomingWeek
import com.maggie.app.data.repository.BudgetRepository
import com.maggie.app.data.repository.CategorizationRuleRepository
import com.maggie.app.data.repository.CategoryRepository
import com.maggie.app.data.repository.ReviewRepository
import com.maggie.app.data.repository.TransactionRepository
import com.maggie.app.data.repository.UpcomingRepository
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/** Activité Comptes · la revue (MAG-456): one test per transition of the wizard, and what each writes to the API. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReviewActivityViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val month = "2026-10"

    private lateinit var review: ReviewRepository
    private lateinit var transactions: TransactionRepository
    private lateinit var categories: CategoryRepository
    private lateinit var budget: BudgetRepository
    private lateinit var upcoming: UpcomingRepository

    private fun line(id: String, recognised: Boolean = false, suggestion: SortSuggestion? = null) = ReviewQueueItem(
        id = id,
        label = "LIGNE $id",
        recognised = recognised,
        amountCents = -1200,
        bookedAt = "2026-10-0${id.last()}",
        accountName = "Courant",
        judgeable = true,
        suggestion = suggestion,
        categoryId = if (recognised) "cat-courses" else null,
        categoryPath = if (recognised) "Alimentation › Courses" else null,
        qualifiedBy = if (recognised) "rule" else null,
    )

    private val five = (1..5).map { line("t$it") }
    private val envelopes = EnvelopeAdjustments(month = month, envelopes = listOf(EnvelopeAdjustment(envelopeId = "env-1", categoryId = "cat-1")))
    private val coming = UpcomingProjection(week = UpcomingWeek(expenses = listOf(UpcomingItem(date = "2026-10-20", label = "Loyer", amountCents = -80000))))

    private fun progress(step: String = REVIEW_STEP_REVIEW, handled: List<String> = emptyList()) =
        ReviewProgress(month = month, step = step, handledTransactionIds = handled)

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        review = mockk()
        transactions = mockk()
        categories = mockk()
        budget = mockk()
        upcoming = mockk()
        coEvery { categories.getCategories() } returns Result.success(listOf(Category(id = "cat-loisirs", name = "Loisirs")))
        coEvery { review.getProgress(month) } returns Result.success(progress())
        coEvery { review.getQueue(month) } returns Result.success(ReviewQueue(month = month, items = five))
        coEvery { review.markHandled(month, any()) } answers { Result.success(progress(handled = listOf(secondArg()))) }
        coEvery { review.moveTo(month, any()) } answers { Result.success(progress(step = secondArg())) }
        coEvery { budget.getEnvelopeAdjustments(month) } returns Result.success(envelopes)
        coEvery { upcoming.getUpcoming() } returns Result.success(coming)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun launch() = ReviewActivityViewModel(
        review,
        transactions,
        categories,
        mockk<CategorizationRuleRepository>(relaxed = true),
        budget,
        upcoming,
        today = LocalDate.of(2026, 10, 25),
    )

    // --- start ---

    @Test
    fun `the review is loading until the progress and the queue are read, then shows the first line`() = runTest {
        val viewModel = launch()
        assertTrue(viewModel.uiState.value.isLoading)

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals(ReviewStep.REVIEW, state.step)
        assertEquals("t1", state.current?.id)
        assertEquals("1 / 5", state.counter)
        assertEquals(ReviewStep.BALANCE, state.nextStep)
        assertFalse(state.canGoBack)
        assertEquals(listOf("Loisirs"), state.categories.map { it.name })
        coVerify(exactly = 0) { review.moveTo(any(), any()) }
    }

    @Test
    fun `a progress that cannot be read leaves an error to retry, and a retry starts again`() = runTest {
        coEvery { review.getProgress(month) } returns Result.failure(RuntimeException("Réseau"))
        val viewModel = launch()
        advanceUntilIdle()

        assertEquals("Réseau", viewModel.uiState.value.loadError)
        assertFalse(viewModel.uiState.value.isLoading)

        coEvery { review.getProgress(month) } returns Result.success(progress())
        viewModel.start()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.loadError)
        assertEquals("t1", viewModel.uiState.value.current?.id)
    }

    @Test
    fun `quitting at the third line and relaunching resumes on the fourth`() = runTest {
        val first = launch()
        advanceUntilIdle()
        repeat(3) {
            first.next()
            advanceUntilIdle()
        }
        assertEquals("t4", first.uiState.value.current?.id)
        coVerify(exactly = 1) { review.markHandled(month, "t1") }
        coVerify(exactly = 1) { review.markHandled(month, "t2") }
        coVerify(exactly = 1) { review.markHandled(month, "t3") }

        // Quit: nothing more to write. The API now holds the three lines.
        coEvery { review.getProgress(month) } returns Result.success(progress(handled = listOf("t1", "t2", "t3")))
        val relaunched = launch()
        advanceUntilIdle()

        val state = relaunched.uiState.value
        assertEquals(ReviewStep.REVIEW, state.step)
        assertEquals("t4", state.current?.id)
        assertEquals("1 / 2", state.counter)
        assertEquals(setOf("t1", "t2", "t3"), state.handled)
    }

    @Test
    fun `a review stored on a later step resumes there`() = runTest {
        coEvery { review.getProgress(month) } returns Result.success(progress(step = REVIEW_STEP_ENVELOPES))
        val viewModel = launch()
        advanceUntilIdle()

        assertEquals(ReviewStep.ENVELOPES, viewModel.uiState.value.step)
        assertNull(viewModel.uiState.value.current)
        coVerify(exactly = 0) { review.moveTo(any(), any()) }
    }

    @Test
    fun `with every line handled the Revue step is skipped and the review starts on Bilan`() = runTest {
        coEvery { review.getProgress(month) } returns Result.success(progress(handled = five.map { it.id }))
        val viewModel = launch()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(ReviewStep.BALANCE, state.step)
        assertTrue(ReviewStep.REVIEW in state.skipped)
        assertFalse("Bilan has nothing before it once Revue is skipped", state.canGoBack)
        coVerify(exactly = 1) { review.moveTo(month, REVIEW_STEP_BALANCE) }
    }

    @Test
    fun `a month already reviewed shows the end, and restarting goes back to Bilan`() = runTest {
        coEvery { review.getProgress(month) } returns Result.success(progress(step = REVIEW_STEP_DONE))
        val viewModel = launch()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.done)

        viewModel.restart()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.done)
        assertEquals(ReviewStep.BALANCE, viewModel.uiState.value.step)
        coVerify(exactly = 1) { review.moveTo(month, REVIEW_STEP_BALANCE) }
    }

    // --- the Revue step ---

    @Test
    fun `Passer on a line with nothing to validate writes it as handled, then the next line comes`() = runTest {
        val viewModel = launch()
        advanceUntilIdle()

        viewModel.next()
        advanceUntilIdle()

        assertEquals("t2", viewModel.uiState.value.current?.id)
        assertEquals("2 / 5", viewModel.uiState.value.counter)
        assertEquals(setOf("t1"), viewModel.uiState.value.handled)
        assertFalse(viewModel.uiState.value.isBusy)
        coVerify(exactly = 1) { review.markHandled(month, "t1") }
        coVerify(exactly = 0) { transactions.sortToCategory(any(), any(), any()) }
    }

    @Test
    fun `Valider on a line with a suggestion files it there, then writes it as handled`() = runTest {
        val suggestion = SortSuggestion(categoryId = "cat-loisirs", categoryPath = "Loisirs", reason = "Règle")
        coEvery { review.getQueue(month) } returns Result.success(ReviewQueue(items = listOf(line("t1", suggestion = suggestion), line("t2"))))
        coEvery { transactions.sortToCategory("t1", "cat-loisirs", false) } returns Result.success(SortResult(success = true))
        val viewModel = launch()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.footerValidates)

        viewModel.next()
        advanceUntilIdle()

        coVerify(exactly = 1) { transactions.sortToCategory("t1", "cat-loisirs", false) }
        coVerify(exactly = 1) { review.markHandled(month, "t1") }
        assertEquals("t2", viewModel.uiState.value.current?.id)
        assertFalse(viewModel.uiState.value.footerValidates)
    }

    @Test
    fun `Valider on a recognised line checks it first, then writes it as handled`() = runTest {
        coEvery { review.getQueue(month) } returns Result.success(ReviewQueue(items = listOf(line("t1", recognised = true), line("t2"))))
        coEvery { transactions.controlRecognised(listOf("t1")) } returns Result.success(ControlResult(controlledCount = 1))
        val viewModel = launch()
        advanceUntilIdle()

        viewModel.next()
        advanceUntilIdle()

        coVerify(exactly = 1) { transactions.controlRecognised(listOf("t1")) }
        coVerify(exactly = 1) { review.markHandled(month, "t1") }
        assertEquals("t2", viewModel.uiState.value.current?.id)
    }

    @Test
    fun `Accepter files the line under Maggie's suggestion and moves on`() = runTest {
        val suggestion = SortSuggestion(categoryId = "cat-loisirs", categoryPath = "Loisirs", reason = "Règle")
        coEvery { review.getQueue(month) } returns Result.success(ReviewQueue(items = listOf(line("t1", suggestion = suggestion), line("t2"))))
        coEvery { transactions.sortToCategory("t1", "cat-loisirs", false) } returns Result.success(SortResult(success = true))
        val viewModel = launch()
        advanceUntilIdle()

        viewModel.accept()
        advanceUntilIdle()

        coVerify(exactly = 1) { transactions.sortToCategory("t1", "cat-loisirs", false) }
        coVerify(exactly = 1) { review.markHandled(month, "t1") }
        assertEquals("t2", viewModel.uiState.value.current?.id)
    }

    @Test
    fun `Changer opens the category search, and the category picked files the line`() = runTest {
        coEvery { transactions.sortToCategory("t1", "cat-loisirs", false) } returns Result.success(SortResult(success = true))
        val viewModel = launch()
        advanceUntilIdle()

        viewModel.openCategorySearch()
        assertTrue(viewModel.uiState.value.choosingCategory)
        viewModel.chooseCategory("cat-loisirs")
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.choosingCategory)
        coVerify(exactly = 1) { transactions.sortToCategory("t1", "cat-loisirs", false) }
        coVerify(exactly = 1) { review.markHandled(month, "t1") }
        assertEquals("t2", viewModel.uiState.value.current?.id)
    }

    @Test
    fun `Corriger on a recognised line keeps what the operation set and changes the category`() = runTest {
        val qualification = Qualification(mandatory = true, kind = QualificationKind.Saving)
        coEvery { review.getQueue(month) } returns Result.success(ReviewQueue(items = listOf(line("t1", recognised = true), line("t2"))))
        coEvery { transactions.getTransaction("t1") } returns Result.success(Transaction(id = "t1", label = "LIGNE t1", qualification = qualification))
        coEvery { transactions.setQualification("t1", "cat-loisirs", qualification) } returns
            Result.success(Transaction(id = "t1", label = "LIGNE t1"))
        val viewModel = launch()
        advanceUntilIdle()

        viewModel.openCategorySearch()
        viewModel.chooseCategory("cat-loisirs")
        advanceUntilIdle()

        coVerify(exactly = 1) { transactions.setQualification("t1", "cat-loisirs", qualification) }
        coVerify(exactly = 1) { review.markHandled(month, "t1") }
        assertEquals("t2", viewModel.uiState.value.current?.id)
    }

    @Test
    fun `a refused sort keeps the line on screen and writes nothing as handled`() = runTest {
        coEvery { transactions.sortToCategory("t1", "cat-loisirs", false) } returns Result.failure(RuntimeException("Refusé"))
        val viewModel = launch()
        advanceUntilIdle()

        viewModel.chooseCategory("cat-loisirs")
        advanceUntilIdle()

        assertEquals("t1", viewModel.uiState.value.current?.id)
        assertEquals("Refusé", viewModel.uiState.value.error)
        assertFalse(viewModel.uiState.value.isBusy)
        coVerify(exactly = 0) { review.markHandled(any(), any()) }
    }

    @Test
    fun `a handled line the API refused to store keeps the line on screen`() = runTest {
        coEvery { review.markHandled(month, "t1") } returns Result.failure(RuntimeException("Hors ligne"))
        val viewModel = launch()
        advanceUntilIdle()

        viewModel.next()
        advanceUntilIdle()

        assertEquals("t1", viewModel.uiState.value.current?.id)
        assertEquals("Hors ligne", viewModel.uiState.value.error)
        assertTrue(viewModel.uiState.value.handled.isEmpty())
    }

    @Test
    fun `a satisfaction judges the line and leaves it on screen`() = runTest {
        coEvery { transactions.setRetrospect("t1", RETROSPECT_AVOIDABLE) } returns Result.success(Unit)
        val viewModel = launch()
        advanceUntilIdle()

        viewModel.judge(RETROSPECT_AVOIDABLE)
        advanceUntilIdle()

        assertEquals(RETROSPECT_AVOIDABLE, viewModel.uiState.value.current?.retrospect)
        assertEquals("t1", viewModel.uiState.value.current?.id)
        coVerify(exactly = 0) { review.markHandled(any(), any()) }
    }

    @Test
    fun `Precedent in the Revue step goes back one line without writing anything`() = runTest {
        val viewModel = launch()
        advanceUntilIdle()
        viewModel.next()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.canGoBack)

        viewModel.previous()
        advanceUntilIdle()

        assertEquals("t1", viewModel.uiState.value.current?.id)
        coVerify(exactly = 0) { review.moveTo(any(), any()) }
    }

    @Test
    fun `the last line handled moves the review to Bilan and writes the step`() = runTest {
        coEvery { review.getQueue(month) } returns Result.success(ReviewQueue(items = listOf(line("t1"))))
        val viewModel = launch()
        advanceUntilIdle()

        viewModel.next()
        advanceUntilIdle()

        assertEquals(ReviewStep.BALANCE, viewModel.uiState.value.step)
        assertNull(viewModel.uiState.value.current)
        coVerify(exactly = 1) { review.markHandled(month, "t1") }
        coVerify(exactly = 1) { review.moveTo(month, REVIEW_STEP_BALANCE) }
    }

    // --- the other steps ---

    @Test
    fun `Suivant moves through the steps, each written, and the last ends the review`() = runTest {
        coEvery { review.getProgress(month) } returns Result.success(progress(step = REVIEW_STEP_BALANCE))
        val viewModel = launch()
        advanceUntilIdle()

        viewModel.next()
        advanceUntilIdle()
        assertEquals(ReviewStep.ENVELOPES, viewModel.uiState.value.step)
        viewModel.next()
        advanceUntilIdle()
        assertEquals(ReviewStep.UPCOMING, viewModel.uiState.value.step)
        viewModel.next()
        advanceUntilIdle()
        assertEquals(ReviewStep.PROJECTS, viewModel.uiState.value.step)
        assertNull("Projets is the last step", viewModel.uiState.value.nextStep)
        viewModel.next()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.done)
        coVerify(exactly = 1) { review.moveTo(month, REVIEW_STEP_ENVELOPES) }
        coVerify(exactly = 1) { review.moveTo(month, REVIEW_STEP_UPCOMING) }
        coVerify(exactly = 1) { review.moveTo(month, REVIEW_STEP_PROJECTS) }
        coVerify(exactly = 1) { review.moveTo(month, REVIEW_STEP_DONE) }
    }

    // --- the review that closes a period (MAG-462) ---

    private fun quarterEnd(step: String) = progress(step = step).copy(
        level = REVIEW_LEVEL_QUARTER,
        period = PlanningPeriodRef("2026-Q4", REVIEW_LEVEL_QUARTER, "2026-10", "2026-12", 3),
        nextPeriod = PlanningPeriodRef("2027-Q1", REVIEW_LEVEL_QUARTER, "2027-01", "2027-03", 3),
    )

    @Test
    fun `a plain month walks five steps and carries no period`() = runTest {
        val viewModel = launch()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(REVIEW_LEVEL_MONTH, state.level)
        assertEquals(ReviewStep.MONTH, state.steps)
        assertNull(state.nextPeriod)
        assertTrue(ReviewStep.PERIOD_BALANCE in state.skipped && ReviewStep.PERIOD_PLAN in state.skipped)
    }

    @Test
    fun `the review that closes a quarter walks seven steps and names the next period`() = runTest {
        coEvery { review.getProgress(month) } returns Result.success(quarterEnd(REVIEW_STEP_PROJECTS))
        val viewModel = launch()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(REVIEW_LEVEL_QUARTER, state.level)
        assertEquals(7, state.steps.size)
        assertEquals("2027-Q1", state.nextPeriod?.key)
        assertEquals(ReviewStep.PERIOD_BALANCE, state.nextStep)
    }

    @Test
    fun `Suivant from Projets goes to the bilan of the period, then to the planning, each written`() = runTest {
        coEvery { review.getProgress(month) } returns Result.success(quarterEnd(REVIEW_STEP_PROJECTS))
        val viewModel = launch()
        advanceUntilIdle()

        viewModel.next()
        advanceUntilIdle()
        assertEquals(ReviewStep.PERIOD_BALANCE, viewModel.uiState.value.step)
        assertEquals("Bilan du trimestre", viewModel.uiState.value.step.titleFor(viewModel.uiState.value.level))
        viewModel.next()
        advanceUntilIdle()
        assertEquals(ReviewStep.PERIOD_PLAN, viewModel.uiState.value.step)
        assertNull("the planning is the last step", viewModel.uiState.value.nextStep)
        viewModel.next()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.done)
        coVerify(exactly = 1) { review.moveTo(month, REVIEW_STEP_PERIOD_BALANCE) }
        coVerify(exactly = 1) { review.moveTo(month, REVIEW_STEP_PERIOD_PLAN) }
        coVerify(exactly = 1) { review.moveTo(month, REVIEW_STEP_DONE) }
    }

    @Test
    fun `a review stored on the planning resumes there`() = runTest {
        coEvery { review.getProgress(month) } returns Result.success(quarterEnd(REVIEW_STEP_PERIOD_PLAN))
        val viewModel = launch()
        advanceUntilIdle()

        assertEquals(ReviewStep.PERIOD_PLAN, viewModel.uiState.value.step)
        assertTrue(viewModel.uiState.value.canGoBack)
        coVerify(exactly = 0) { review.moveTo(any(), any()) }
    }

    @Test
    fun `a month review stored on a period step falls back on the steps it walks`() = runTest {
        coEvery { review.getProgress(month) } returns Result.success(progress(step = REVIEW_STEP_PERIOD_PLAN))
        val viewModel = launch()
        advanceUntilIdle()

        assertEquals(ReviewStep.PROJECTS, viewModel.uiState.value.step)
    }

    @Test
    fun `a month with no envelope and nothing to come skips Enveloppes and A venir`() = runTest {
        coEvery { review.getProgress(month) } returns Result.success(progress(step = REVIEW_STEP_BALANCE))
        coEvery { budget.getEnvelopeAdjustments(month) } returns Result.success(EnvelopeAdjustments(month = month))
        coEvery { upcoming.getUpcoming() } returns Result.success(UpcomingProjection())
        val viewModel = launch()
        advanceUntilIdle()
        assertEquals(ReviewStep.PROJECTS, viewModel.uiState.value.nextStep)

        viewModel.next()
        advanceUntilIdle()

        assertEquals(ReviewStep.PROJECTS, viewModel.uiState.value.step)
        assertEquals(setOf(ReviewStep.ENVELOPES, ReviewStep.UPCOMING), viewModel.uiState.value.skipped)
        coVerify(exactly = 0) { review.moveTo(month, REVIEW_STEP_ENVELOPES) }
        coVerify(exactly = 0) { review.moveTo(month, REVIEW_STEP_UPCOMING) }
        coVerify(exactly = 1) { review.moveTo(month, REVIEW_STEP_PROJECTS) }
    }

    @Test
    fun `envelopes that cannot be read do not skip the step, which says so itself`() = runTest {
        coEvery { budget.getEnvelopeAdjustments(month) } returns Result.failure(RuntimeException("Réseau"))
        val viewModel = launch()
        advanceUntilIdle()

        assertFalse(ReviewStep.ENVELOPES in viewModel.uiState.value.skipped)
    }

    @Test
    fun `Precedent from Bilan goes back to the last line of Revue and writes the step`() = runTest {
        coEvery { review.getProgress(month) } returns Result.success(progress(step = REVIEW_STEP_BALANCE, handled = listOf("t1")))
        val viewModel = launch()
        advanceUntilIdle()
        assertEquals(ReviewStep.BALANCE, viewModel.uiState.value.step)

        viewModel.previous()
        advanceUntilIdle()

        assertEquals(ReviewStep.REVIEW, viewModel.uiState.value.step)
        assertEquals("t5", viewModel.uiState.value.current?.id)
        coVerify(exactly = 1) { review.moveTo(month, REVIEW_STEP_REVIEW) }
    }

    @Test
    fun `a step the API refused to store keeps the step on screen`() = runTest {
        coEvery { review.getProgress(month) } returns Result.success(progress(step = REVIEW_STEP_BALANCE))
        coEvery { review.moveTo(month, REVIEW_STEP_ENVELOPES) } returns Result.failure(RuntimeException("Hors ligne"))
        val viewModel = launch()
        advanceUntilIdle()

        viewModel.next()
        advanceUntilIdle()

        assertEquals(ReviewStep.BALANCE, viewModel.uiState.value.step)
        assertNotNull(viewModel.uiState.value.error)
        assertFalse(viewModel.uiState.value.isBusy)
    }
}

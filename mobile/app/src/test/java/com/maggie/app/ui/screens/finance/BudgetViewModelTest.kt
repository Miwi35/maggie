package com.maggie.app.ui.screens.finance

import com.maggie.app.data.api.EnvelopeCreateRequest
import com.maggie.app.data.api.RollOverRequest
import com.maggie.app.data.model.BudgetLine
import com.maggie.app.data.model.BudgetStatus
import com.maggie.app.data.model.Category
import com.maggie.app.data.model.DailyScore
import com.maggie.app.data.model.Envelope
import com.maggie.app.data.model.OneOffBudgetLine
import com.maggie.app.data.model.OutsideMonthlyBudget
import com.maggie.app.data.model.RollOverResult
import com.maggie.app.data.model.Season
import com.maggie.app.data.repository.BudgetRepository
import com.maggie.app.data.repository.CategoryRepository
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class BudgetViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var budgetRepository: BudgetRepository
    private lateinit var categoryRepository: CategoryRepository
    private lateinit var viewModel: BudgetViewModel

    private val today = LocalDate.of(2026, 7, 15)

    private val sampleStatus = BudgetStatus(
        year = 2026,
        month = 7,
        totalBudgetedCents = 160000,
        totalSpentCents = 5799,
        totalRemainingCents = 154201,
        budgets = listOf(
            BudgetLine(
                id = "env-1",
                categoryId = "cat-1",
                categoryName = "Alimentation",
                mode = "monthly",
                amountCents = 40000,
                year = 2026,
                month = 7,
                spentCents = 4599,
                consumedCents = 4599,
                remainingCents = 35401,
                availableCents = 35401,
            ),
        ),
    )

    private val sampleCategories = listOf(
        Category(id = "cat-2", name = "Loisirs", obligation = "optional"),
        Category(id = "cat-1", name = "Alimentation", obligation = "mandatory"),
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        budgetRepository = mockk()
        categoryRepository = mockk()
        coEvery { budgetRepository.getBudgetStatus(any(), any()) } returns Result.success(sampleStatus)
        coEvery { budgetRepository.getDailyScore(any(), any()) } returns
            Result.success(DailyScore(score = "green", year = 2026, month = 7))
        coEvery { categoryRepository.getCategories() } returns Result.success(sampleCategories)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun buildViewModel() = BudgetViewModel(budgetRepository, categoryRepository, today)

    @Test
    fun `initial load fetches the current period and its categories`() = runTest {
        viewModel = buildViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(2026, state.year)
        assertEquals(7, state.month)
        assertEquals(sampleStatus, state.status)
        assertEquals("Alimentation", state.categories[0].name)
        assertFalse(state.isLoading)
        assertNull(state.error)

        coVerify { budgetRepository.getBudgetStatus(2026, 7) }
    }

    @Test
    fun `the annual and one-off envelopes come apart from the month's budget`() = runTest {
        coEvery { budgetRepository.getBudgetStatus(2026, 7) } returns Result.success(
            BudgetStatus(
                year = 2026,
                month = 7,
                totalBudgetedCents = 30000,
                budgets = listOf(
                    BudgetLine(id = "env-loisirs", categoryId = "cat-loisirs", categoryName = "Loisirs", categoryPath = "Loisirs", amountCents = 30000, consumedCents = 2500),
                ),
                outsideMonthlyBudget = OutsideMonthlyBudget(
                    annual = listOf(
                        BudgetLine(id = "env-voyages", categoryId = "cat-voyages", categoryName = "Voyages", categoryPath = "Voyages", mode = "annual", amountCents = 120000, consumedCents = 6000),
                    ),
                    oneOff = listOf(
                        OneOffBudgetLine(id = "env-hellfest", name = "Hellfest 2026", amountCents = 40000, spentCents = 8900, consumedCents = 8900, remainingCents = 31100),
                    ),
                ),
            ),
        )
        viewModel = buildViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf("env-loisirs"), state.nestedBudgets.map { it.line.id })
        assertEquals(listOf("env-voyages"), state.nestedAnnualBudgets.map { it.line.id })
        assertEquals("Hellfest 2026", state.status?.outsideMonthlyBudget?.oneOff?.single()?.name)
        assertEquals(8900, state.status?.outsideMonthlyBudget?.oneOff?.single()?.consumedCents)
        assertEquals(30000, state.status?.totalBudgetedCents)
    }

    @Test
    fun `a spend in a sub-category counts in its envelope and in every mother, nested under it`() = runTest {
        coEvery { budgetRepository.getBudgetStatus(2026, 7) } returns Result.success(
            BudgetStatus(
                year = 2026,
                month = 7,
                totalBudgetedCents = 30000,
                totalSpentCents = 4000,
                budgets = listOf(
                    BudgetLine(
                        id = "env-obsydian",
                        categoryId = "cat-obsydian",
                        categoryName = "Obsydian",
                        categoryPath = "Loisirs › Bars › Obsydian",
                        parentEnvelopeId = "env-loisirs",
                        amountCents = 10000,
                        spentCents = 4000,
                        consumedCents = 4000,
                    ),
                    BudgetLine(
                        id = "env-loisirs",
                        categoryId = "cat-loisirs",
                        categoryName = "Loisirs",
                        categoryPath = "Loisirs",
                        amountCents = 30000,
                        spentCents = 4000,
                        consumedCents = 4000,
                    ),
                ),
            ),
        )
        viewModel = buildViewModel()
        advanceUntilIdle()

        val rows = viewModel.uiState.value.nestedBudgets
        assertEquals(listOf("env-loisirs", "env-obsydian"), rows.map { it.line.id })
        assertEquals(listOf(1, 2), rows.map { it.depth })
        assertEquals("Bars › Obsydian", rows[1].label)
        assertEquals(30000, viewModel.uiState.value.status?.totalBudgetedCents)
    }

    @Test
    fun `nesting follows the period shown, an empty period nests nothing`() = runTest {
        coEvery { budgetRepository.getBudgetStatus(2026, 6) } returns
            Result.success(BudgetStatus(year = 2026, month = 6))
        viewModel = buildViewModel()
        advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.nestedBudgets.size)

        viewModel.shiftPeriod(-1)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.nestedBudgets.isEmpty())
    }

    @Test
    fun `shiftPeriod moves to the previous month and reloads`() = runTest {
        viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.shiftPeriod(-1)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(2026, state.year)
        assertEquals(6, state.month)
        coVerify { budgetRepository.getBudgetStatus(2026, 6) }
    }

    @Test
    fun `shiftPeriod rolls over to the next year in December`() = runTest {
        viewModel = BudgetViewModel(budgetRepository, categoryRepository, LocalDate.of(2026, 12, 1))
        advanceUntilIdle()

        viewModel.shiftPeriod(1)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(2027, state.year)
        assertEquals(1, state.month)
    }

    @Test
    fun `createEnvelope dispatches the request and reloads the status`() = runTest {
        coEvery { budgetRepository.createEnvelope(any()) } returns Result.success(
            Envelope(id = "env-2", amountCents = 15000, year = 2026, month = 7),
        )
        viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.createEnvelope(
            EnvelopeCreateRequest(
                category = "/api/categories/cat-2",
                amountCents = 15000,
                year = 2026,
                month = 7,
            ),
        )
        advanceUntilIdle()

        coVerify { budgetRepository.createEnvelope(any()) }
        coVerify(atLeast = 2) { budgetRepository.getBudgetStatus(2026, 7) }
    }

    @Test
    fun `setAlertThreshold saves it and reloads the status (MAG-49)`() = runTest {
        coEvery { budgetRepository.setAlertThreshold("env-1", 90) } returns
            Result.success(Envelope(id = "env-1", amountCents = 40000, year = 2026, month = 7))
        viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.setAlertThreshold("env-1", 90)
        advanceUntilIdle()

        coVerify { budgetRepository.setAlertThreshold("env-1", 90) }
        coVerify(atLeast = 2) { budgetRepository.getBudgetStatus(2026, 7) }
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `createEnvelope sends the seasons of a monthly envelope`() = runTest {
        coEvery { budgetRepository.createEnvelope(any()) } returns Result.success(
            Envelope(id = "env-3", amountCents = 2000, year = 2026, month = 7),
        )
        viewModel = buildViewModel()
        advanceUntilIdle()
        val request = EnvelopeCreateRequest(
            category = "/api/categories/cat-2",
            amountCents = 2000,
            year = 2026,
            month = 7,
            seasonalAmounts = listOf(Season(fromMonth = 11, toMonth = 2, amountCents = 4500)),
        )

        viewModel.createEnvelope(request)
        advanceUntilIdle()

        coVerify { budgetRepository.createEnvelope(request) }
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `setAlertThreshold null puts the default back (MAG-49)`() = runTest {
        coEvery { budgetRepository.setAlertThreshold("env-1", null) } returns
            Result.success(Envelope(id = "env-1", amountCents = 40000, year = 2026, month = 7))
        viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.setAlertThreshold("env-1", null)
        advanceUntilIdle()

        coVerify { budgetRepository.setAlertThreshold("env-1", null) }
    }

    @Test
    fun `a refused threshold says why and keeps the status (MAG-49)`() = runTest {
        coEvery { budgetRepository.setAlertThreshold("env-1", 90) } returns
            Result.failure(RuntimeException("alertThresholdPercent: This value should be between 1 and 99."))
        viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.setAlertThreshold("env-1", 90)
        advanceUntilIdle()

        assertEquals("alertThresholdPercent: This value should be between 1 and 99.", viewModel.uiState.value.error)
        assertEquals(sampleStatus, viewModel.uiState.value.status)
    }

    @Test
    fun `createEnvelope refuses two seasons sharing a month without calling the API`() = runTest {
        viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.createEnvelope(
            EnvelopeCreateRequest(
                category = "/api/categories/cat-2",
                amountCents = 2000,
                year = 2026,
                month = 7,
                seasonalAmounts = listOf(Season(11, 2, 4500), Season(2, 3, 3000)),
            ),
        )
        advanceUntilIdle()

        coVerify(exactly = 0) { budgetRepository.createEnvelope(any()) }
        assertEquals(
            "Deux saisons couvrent février : une saison ne peut pas en chevaucher une autre.",
            viewModel.uiState.value.error,
        )
    }

    @Test
    fun `a month in its season carries the season to the screen`() = runTest {
        val winter = Season(fromMonth = 11, toMonth = 2, amountCents = 4500)
        coEvery { budgetRepository.getBudgetStatus(2026, 11) } returns Result.success(
            BudgetStatus(
                year = 2026,
                month = 11,
                budgets = listOf(
                    BudgetLine(
                        id = "cinema", categoryId = "cat-3", categoryName = "Cinéma",
                        amountCents = 4500, year = 2026, month = 11, season = winter,
                    ),
                ),
            ),
        )
        viewModel = BudgetViewModel(budgetRepository, categoryRepository, LocalDate.of(2026, 11, 3))
        advanceUntilIdle()

        val line = viewModel.uiState.value.nestedBudgets.single().line
        assertEquals(4500, line.amountCents)
        assertEquals(winter, line.season)
    }

    @Test
    fun `deleteEnvelope reloads the status`() = runTest {
        coEvery { budgetRepository.deleteEnvelope("env-1") } returns Result.success(Unit)
        viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.deleteEnvelope("env-1")
        advanceUntilIdle()

        coVerify { budgetRepository.deleteEnvelope("env-1") }
        coVerify(atLeast = 2) { budgetRepository.getBudgetStatus(2026, 7) }
    }

    @Test
    fun `rollOverPreviousPeriod copies the previous month onto the shown one`() = runTest {
        coEvery { budgetRepository.rollOverEnvelopes(any()) } returns Result.success(
            RollOverResult(success = true, created = 2, skipped = 1),
        )
        viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.rollOverPreviousPeriod()
        advanceUntilIdle()

        coVerify {
            budgetRepository.rollOverEnvelopes(
                RollOverRequest(fromYear = 2026, fromMonth = 6, year = 2026, month = 7),
            )
        }

        val state = viewModel.uiState.value
        assertFalse(state.isRollingOver)
        assertEquals("2 enveloppe(s) reconduite(s)", state.rollOverMessage)
    }

    @Test
    fun `rollOverPreviousPeriod says so when there is nothing to carry over`() = runTest {
        coEvery { budgetRepository.rollOverEnvelopes(any()) } returns Result.success(
            RollOverResult(success = true, created = 0, skipped = 3),
        )
        viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.rollOverPreviousPeriod()
        advanceUntilIdle()

        assertEquals(
            "Rien à reconduire : les enveloppes sont déjà en place",
            viewModel.uiState.value.rollOverMessage,
        )

        viewModel.clearRollOverMessage()
        assertNull(viewModel.uiState.value.rollOverMessage)
    }

    @Test
    fun `rollOver failure surfaces the error and stops the spinner`() = runTest {
        coEvery { budgetRepository.rollOverEnvelopes(any()) } returns
            Result.failure(RuntimeException("boom"))
        viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.rollOverPreviousPeriod()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("boom", state.error)
        assertFalse(state.isRollingOver)
    }

    @Test
    fun `initial load also brings back the daily score`() = runTest {
        viewModel = buildViewModel()
        advanceUntilIdle()

        assertEquals("green", viewModel.uiState.value.score?.score)
        coVerify { budgetRepository.getDailyScore(2026, 7) }
    }

    @Test
    fun `a failing score does not cost the user their budget view`() = runTest {
        coEvery { budgetRepository.getDailyScore(any(), any()) } returns
            Result.failure(RuntimeException("boom"))

        viewModel = buildViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(sampleStatus, state.status)
        assertNull(state.score)
        assertNull(state.error)
    }

    @Test
    fun `load failure sets error`() = runTest {
        coEvery { budgetRepository.getBudgetStatus(any(), any()) } returns
            Result.failure(RuntimeException("boom"))

        viewModel = buildViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("boom", state.error)
        assertNull(state.status)
        assertTrue(state.categories.isNotEmpty())
    }
}

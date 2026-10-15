package com.maggie.app.ui.screens.finance

import com.maggie.app.data.api.ApiRefusalException
import com.maggie.app.data.api.EnvelopeCreateRequest
import com.maggie.app.data.api.EnvelopeUpdateRequest
import com.maggie.app.data.api.OneOffEnvelopeCreateRequest
import com.maggie.app.data.model.BudgetLine
import com.maggie.app.data.model.BudgetStatus
import com.maggie.app.data.model.Category
import com.maggie.app.data.model.DailyScore
import com.maggie.app.data.model.Envelope
import com.maggie.app.data.model.ONE_OFF_MODE
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
import kotlinx.coroutines.test.TestScope
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

/**
 * The tablet's envelope editor (MAG-419): one test per transition of the tree and its right pane.
 * The story is the owner's: Loisirs > Bars > Obsydian, Loisirs > Cinéma; Obsydian has no envelope.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BudgetConfigurationViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var budgetRepository: BudgetRepository
    private lateinit var categoryRepository: CategoryRepository
    private lateinit var viewModel: BudgetViewModel

    private val today = LocalDate.of(2026, 7, 15)

    private val categories = listOf(
        Category(id = "cat-obsydian", name = "Obsydian", parent = "/api/categories/cat-bars"),
        Category(id = "cat-cinema", name = "Cinéma", parent = "/api/categories/cat-loisirs"),
        Category(id = "cat-bars", name = "Bars", parent = "/api/categories/cat-loisirs"),
        Category(id = "cat-loisirs", name = "Loisirs"),
        Category(id = "cat-voyages", name = "Voyages"),
    )

    private val status = BudgetStatus(
        year = 2026,
        month = 7,
        budgets = listOf(
            BudgetLine(
                id = "env-loisirs", categoryId = "cat-loisirs", categoryName = "Loisirs", categoryPath = "Loisirs",
                amountCents = 30000, consumedCents = 12000, allocatedToChildrenCents = 5000, freeCents = 25000,
                year = 2026, month = 7,
            ),
            BudgetLine(
                id = "env-cinema", categoryId = "cat-cinema", categoryName = "Cinéma", categoryPath = "Loisirs › Cinéma",
                parentEnvelopeId = "env-loisirs",
                // July is outside the winter season: the line carries the base amount here.
                amountCents = 2000, year = 2026, month = 7,
            ),
        ),
        outsideMonthlyBudget = OutsideMonthlyBudget(
            annual = listOf(
                BudgetLine(
                    id = "env-voyages", categoryId = "cat-voyages", categoryName = "Voyages", categoryPath = "Voyages",
                    mode = "annual", amountCents = 120000, year = 2026,
                ),
            ),
            oneOff = listOf(OneOffBudgetLine(id = "env-hellfest", name = "Hellfest 2026", amountCents = 40000)),
        ),
    )

    private val storedCinema = Envelope(
        id = "env-cinema", mode = "monthly", amountCents = 2000, year = 2026, month = 7,
        category = "/api/categories/cat-cinema",
        seasonalAmounts = listOf(Season(11, 2, 4500)),
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        budgetRepository = mockk()
        categoryRepository = mockk()
        coEvery { budgetRepository.getBudgetStatus(any(), any()) } returns Result.success(status)
        coEvery { budgetRepository.getDailyScore(any(), any()) } returns
            Result.success(DailyScore(score = "green", year = 2026, month = 7))
        coEvery { categoryRepository.getCategories() } returns Result.success(categories)
        coEvery { budgetRepository.getEnvelope("env-cinema") } returns Result.success(storedCinema)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.open(): BudgetViewModel {
        viewModel = BudgetViewModel(budgetRepository, categoryRepository, today)
        advanceUntilIdle()
        return viewModel
    }

    @Test
    fun `the tree lists every category in tree order with the envelope of the month, or none`() = runTest {
        val rows = open().uiState.value.treeRows

        assertEquals(listOf("Loisirs", "Bars", "Obsydian", "Cinéma", "Voyages"), rows.map { it.row.category.name })
        assertEquals(listOf(1, 2, 3, 2, 1), rows.map { it.row.level })
        assertEquals("env-loisirs", rows.first { it.row.category.name == "Loisirs" }.line?.id)
        assertNull(rows.first { it.row.category.name == "Obsydian" }.line)
        assertNull(rows.first { it.row.category.name == "Bars" }.line)
    }

    @Test
    fun `touching a category with no envelope opens the create editor`() = runTest {
        open().selectCategory("cat-obsydian")

        val editor = viewModel.uiState.value.editor
        assertEquals(EditorTarget.NewForCategory("cat-obsydian"), editor?.target)
        assertNull(editor?.stored)
        assertEquals("cat-obsydian", viewModel.uiState.value.selectedCategoryId)
        coVerify(exactly = 0) { budgetRepository.getEnvelope(any()) }
    }

    @Test
    fun `creating Obsydian at 100 euros monthly sends the category, the period and reloads`() = runTest {
        val created = Envelope(
            id = "env-obsydian", amountCents = 10000, year = 2026, month = 7,
            category = "/api/categories/cat-obsydian",
        )
        val request = EnvelopeCreateRequest(
            category = "/api/categories/cat-obsydian", amountCents = 10000, year = 2026, mode = "monthly", month = 7,
        )
        coEvery { budgetRepository.createEnvelope(request) } returns Result.success(created)
        open().selectCategory("cat-obsydian")

        viewModel.createEnvelope(request)
        advanceUntilIdle()

        coVerify { budgetRepository.createEnvelope(request) }
        coVerify(atLeast = 2) { budgetRepository.getBudgetStatus(2026, 7) }
        // The editor stays, now on the envelope it made.
        assertEquals(EditorTarget.Existing("env-obsydian"), viewModel.uiState.value.editor?.target)
        assertEquals(created, viewModel.uiState.value.editor?.stored)
        assertFalse(viewModel.uiState.value.editor!!.isSaving)
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `touching a category with an envelope loads the envelope as the API stores it`() = runTest {
        open().selectCategory("cat-cinema")
        assertTrue(viewModel.uiState.value.editor!!.isLoading)

        advanceUntilIdle()

        val editor = viewModel.uiState.value.editor!!
        assertEquals(EditorTarget.Existing("env-cinema"), editor.target)
        assertFalse(editor.isLoading)
        assertEquals(2000, editor.stored?.amountCents)
        assertEquals(listOf(Season(11, 2, 4500)), editor.stored?.seasonalAmounts)
        assertEquals("cat-cinema", viewModel.uiState.value.selectedCategoryId)
    }

    @Test
    fun `saving Cinema's seasons writes them with the base amount`() = runTest {
        val request = EnvelopeUpdateRequest(
            amountCents = 2000, mode = "monthly", month = 7,
            seasonalAmounts = listOf(Season(11, 2, 4500), Season(6, 8, 1500)),
        )
        coEvery { budgetRepository.updateEnvelope("env-cinema", request) } returns
            Result.success(storedCinema.copy(seasonalAmounts = request.seasonalAmounts))
        open().selectCategory("cat-cinema")
        advanceUntilIdle()

        viewModel.updateEnvelope("env-cinema", request)
        advanceUntilIdle()

        coVerify { budgetRepository.updateEnvelope("env-cinema", request) }
        assertEquals(request.seasonalAmounts, viewModel.uiState.value.editor?.stored?.seasonalAmounts)
        coVerify(atLeast = 2) { budgetRepository.getBudgetStatus(2026, 7) }
    }

    @Test
    fun `setting Loisirs' threshold to 90 writes it`() = runTest {
        val request = EnvelopeUpdateRequest(amountCents = 30000, mode = "monthly", month = 7, alertThresholdPercent = 90)
        coEvery { budgetRepository.getEnvelope("env-loisirs") } returns
            Result.success(Envelope(id = "env-loisirs", amountCents = 30000, year = 2026, month = 7))
        coEvery { budgetRepository.updateEnvelope("env-loisirs", request) } returns
            Result.success(Envelope(id = "env-loisirs", amountCents = 30000, year = 2026, month = 7, effectiveAlertThresholdPercent = 90))
        open().selectCategory("cat-loisirs")
        advanceUntilIdle()

        viewModel.updateEnvelope("env-loisirs", request)
        advanceUntilIdle()

        coVerify { budgetRepository.updateEnvelope("env-loisirs", request) }
        assertEquals(90, viewModel.uiState.value.editor?.stored?.effectiveAlertThresholdPercent)
    }

    @Test
    fun `a refusal says why and leaves the editor open`() = runTest {
        val request = EnvelopeUpdateRequest(amountCents = 2000, mode = "monthly", month = 7)
        coEvery { budgetRepository.updateEnvelope("env-cinema", request) } returns
            Result.failure(ApiRefusalException(400, "Seasons only apply to monthly envelopes."))
        open().selectCategory("cat-cinema")
        advanceUntilIdle()

        viewModel.updateEnvelope("env-cinema", request)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("Seasons only apply to monthly envelopes.", state.error)
        assertEquals(EditorTarget.Existing("env-cinema"), state.editor?.target)
        assertEquals(storedCinema, state.editor?.stored)
        assertFalse(state.editor!!.isSaving)
    }

    @Test
    fun `a refused creation keeps the create editor open`() = runTest {
        val request = EnvelopeCreateRequest("/api/categories/cat-obsydian", 10000, 2026, "monthly", 7)
        coEvery { budgetRepository.createEnvelope(request) } returns
            Result.failure(ApiRefusalException(422, "Cette catégorie a déjà une enveloppe."))
        open().selectCategory("cat-obsydian")

        viewModel.createEnvelope(request)
        advanceUntilIdle()

        assertEquals("Cette catégorie a déjà une enveloppe.", viewModel.uiState.value.error)
        assertEquals(EditorTarget.NewForCategory("cat-obsydian"), viewModel.uiState.value.editor?.target)
    }

    @Test
    fun `two seasons sharing a month are refused before any call, the editor stays`() = runTest {
        open().selectCategory("cat-cinema")
        advanceUntilIdle()

        viewModel.updateEnvelope(
            "env-cinema",
            EnvelopeUpdateRequest(2000, "monthly", 7, seasonalAmounts = listOf(Season(11, 2, 4500), Season(2, 3, 3000))),
        )
        advanceUntilIdle()

        coVerify(exactly = 0) { budgetRepository.updateEnvelope(any(), any()) }
        assertEquals(
            "Deux saisons couvrent février : une saison ne peut pas en chevaucher une autre.",
            viewModel.uiState.value.error,
        )
        assertNotNull(viewModel.uiState.value.editor)
    }

    @Test
    fun `deleting the envelope closes the editor and reloads`() = runTest {
        coEvery { budgetRepository.deleteEnvelope("env-cinema") } returns Result.success(Unit)
        open().selectCategory("cat-cinema")
        advanceUntilIdle()

        viewModel.deleteEnvelope("env-cinema")
        advanceUntilIdle()

        coVerify { budgetRepository.deleteEnvelope("env-cinema") }
        coVerify(atLeast = 2) { budgetRepository.getBudgetStatus(2026, 7) }
        assertNull(viewModel.uiState.value.editor)
    }

    @Test
    fun `a failed deletion keeps the editor and says why`() = runTest {
        coEvery { budgetRepository.deleteEnvelope("env-cinema") } returns Result.failure(RuntimeException("boom"))
        open().selectCategory("cat-cinema")
        advanceUntilIdle()

        viewModel.deleteEnvelope("env-cinema")
        advanceUntilIdle()

        assertEquals("boom", viewModel.uiState.value.error)
        assertNotNull(viewModel.uiState.value.editor)
    }

    @Test
    fun `a one-off is created from its name and amount, then edited as a one-off`() = runTest {
        val created = Envelope(id = "env-new", mode = ONE_OFF_MODE, amountCents = 25000, name = "Vacances")
        coEvery {
            budgetRepository.createOneOffEnvelope(OneOffEnvelopeCreateRequest("Vacances", 25000, ONE_OFF_MODE))
        } returns Result.success(created)
        open().startNewOneOff()
        assertEquals(EditorTarget.NewOneOff, viewModel.uiState.value.editor?.target)

        viewModel.createOneOffEnvelope(" Vacances ", 25000)
        advanceUntilIdle()

        coVerify { budgetRepository.createOneOffEnvelope(OneOffEnvelopeCreateRequest("Vacances", 25000, ONE_OFF_MODE)) }
        assertEquals(EditorTarget.Existing("env-new"), viewModel.uiState.value.editor?.target)
        assertTrue(viewModel.uiState.value.editor!!.stored!!.isOneOff)
    }

    @Test
    fun `tapping a one-off line loads it, an annual line too`() = runTest {
        coEvery { budgetRepository.getEnvelope("env-hellfest") } returns
            Result.success(Envelope(id = "env-hellfest", mode = ONE_OFF_MODE, amountCents = 40000, name = "Hellfest 2026"))
        coEvery { budgetRepository.getEnvelope("env-voyages") } returns
            Result.success(Envelope(id = "env-voyages", mode = "annual", amountCents = 120000, year = 2026, category = "/api/categories/cat-voyages"))
        open().selectEnvelope("env-hellfest")
        advanceUntilIdle()
        assertEquals("Hellfest 2026", viewModel.uiState.value.editor?.stored?.name)
        assertEquals("env-hellfest", viewModel.uiState.value.selectedEnvelopeId)

        viewModel.selectEnvelope("env-voyages")
        advanceUntilIdle()

        assertEquals("env-voyages", viewModel.uiState.value.selectedEnvelopeId)
        assertEquals("cat-voyages", viewModel.uiState.value.selectedCategoryId)
    }

    @Test
    fun `an envelope that cannot be loaded closes the editor and says why`() = runTest {
        coEvery { budgetRepository.getEnvelope("env-cinema") } returns Result.failure(RuntimeException("boom"))
        open().selectCategory("cat-cinema")
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.editor)
        assertEquals("boom", viewModel.uiState.value.error)
    }

    @Test
    fun `closing the editor drops the selection`() = runTest {
        open().selectCategory("cat-obsydian")

        viewModel.closeEditor()

        assertNull(viewModel.uiState.value.editor)
        assertNull(viewModel.uiState.value.selectedCategoryId)
    }

    @Test
    fun `touching another node opens its editor and drops a ruler being moved`() = runTest {
        coEvery { budgetRepository.getEnvelope("env-loisirs") } returns
            Result.success(Envelope(id = "env-loisirs", amountCents = 30000, year = 2026, month = 7))
        open().selectCategory("cat-loisirs")
        advanceUntilIdle()
        viewModel.startSplit("env-loisirs")

        viewModel.selectCategory("cat-obsydian")

        assertEquals(EditorTarget.NewForCategory("cat-obsydian"), viewModel.uiState.value.editor?.target)
        assertNull(viewModel.uiState.value.splitEditor)
    }

    @Test
    fun `changing period closes the editor, what was typed for a month is not saved on another`() = runTest {
        open().selectCategory("cat-obsydian")

        viewModel.shiftPeriod(1)
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.editor)
        assertEquals(8, viewModel.uiState.value.month)
    }

    @Test
    fun `a create editor becomes the envelope's editor once a roll-over made it`() = runTest {
        coEvery { budgetRepository.rollOverEnvelopes(any()) } returns Result.success(RollOverResult(true, 1, 0))
        coEvery { budgetRepository.getEnvelope("env-cinema") } returns Result.success(storedCinema)
        val withoutCinema = status.copy(budgets = status.budgets.filter { it.id != "env-cinema" })
        coEvery { budgetRepository.getBudgetStatus(2026, 7) } returns Result.success(withoutCinema)
        open().selectCategory("cat-cinema")
        assertEquals(EditorTarget.NewForCategory("cat-cinema"), viewModel.uiState.value.editor?.target)
        coEvery { budgetRepository.getBudgetStatus(2026, 7) } returns Result.success(status)

        viewModel.rollOverPreviousPeriod()
        advanceUntilIdle()

        assertEquals(EditorTarget.Existing("env-cinema"), viewModel.uiState.value.editor?.target)
        assertEquals(storedCinema, viewModel.uiState.value.editor?.stored)
    }
}

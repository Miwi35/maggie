package com.maggie.app.ui.screens.finance

import com.maggie.app.data.model.Category
import com.maggie.app.data.model.ControlResult
import com.maggie.app.data.model.OPERATION_RULE
import com.maggie.app.data.model.OPERATION_SERIES
import com.maggie.app.data.model.Qualification
import com.maggie.app.data.model.QualificationKind
import com.maggie.app.data.model.RETROSPECT_AVOIDABLE
import com.maggie.app.data.model.RETROSPECT_KEEP
import com.maggie.app.data.model.RETROSPECT_UNRATED
import com.maggie.app.data.model.RecognisedGroup
import com.maggie.app.data.model.RecognisedLine
import com.maggie.app.data.model.RecognisedToControl
import com.maggie.app.data.model.Transaction
import com.maggie.app.data.repository.CategoryRepository
import com.maggie.app.data.repository.TransactionRepository
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

@OptIn(ExperimentalCoroutinesApi::class)
class RecognisedToControlViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var transactions: TransactionRepository
    private lateinit var categories: CategoryRepository

    private fun line(id: String, amountCents: Int = -2000, judgeable: Boolean = true, retrospect: String = RETROSPECT_UNRATED) =
        RecognisedLine(
            id = id,
            label = "CARREFOUR $id",
            amountCents = amountCents,
            bookedAt = "2026-10-1${id.last()}",
            accountId = "acc-1",
            accountName = "Courant",
            judgeable = judgeable,
            retrospect = retrospect,
            categoryId = "cat-courses",
            categoryPath = "Alimentation › Courses",
            qualifiedBy = "rule",
            qualifiedByRuleId = "rule-1",
        )

    private val groceries = RecognisedGroup(
        operationType = OPERATION_RULE,
        operationId = "rule-1",
        label = "Courses",
        count = 3,
        totalCents = -6000,
        unratedCount = 3,
        transactions = listOf(line("t1"), line("t2"), line("t3")),
    )

    private val salary = RecognisedGroup(
        operationType = OPERATION_SERIES,
        operationId = "series-1",
        label = "Salaire",
        count = 1,
        totalCents = 250000,
        unratedCount = 0,
        transactions = listOf(line("t4", amountCents = 250000, judgeable = false)),
    )

    private val data = RecognisedToControl(month = "2026-10", total = 4, groups = listOf(groceries, salary))

    private val categoryList = listOf(
        Category(id = "cat-food", name = "Alimentation"),
        Category(id = "cat-courses", name = "Courses", parent = "/api/categories/cat-food"),
        Category(id = "cat-loisirs", name = "Loisirs"),
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        transactions = mockk()
        categories = mockk()
        coEvery { categories.getCategories() } returns Result.success(categoryList)
        coEvery { transactions.getRecognisedToControl("2026-10") } returns Result.success(data)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun load() = RecognisedToControlViewModel(transactions, categories, today = LocalDate.of(2026, 10, 17))

    @Test
    fun `the list is loading until the server has answered, and asks for this month`() = runTest {
        val viewModel = load()

        assertTrue(viewModel.uiState.value.isLoading)
        assertFalse(viewModel.uiState.value.loaded)
        assertFalse(viewModel.uiState.value.isEmpty)

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertTrue(state.loaded)
        assertEquals(listOf("Courses", "Salaire"), state.data.groups.map { it.label })
        assertEquals(4, state.data.total)
        assertEquals(categoryList, state.categories)
        coVerify(exactly = 1) { transactions.getRecognisedToControl("2026-10") }
    }

    @Test
    fun `an empty month is said once it was read`() = runTest {
        coEvery { transactions.getRecognisedToControl("2026-10") } returns Result.success(RecognisedToControl(month = "2026-10"))
        val viewModel = load()

        assertFalse(viewModel.uiState.value.isEmpty)

        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isEmpty)
    }

    @Test
    fun `a list that cannot be read is an error to retry, not an empty month`() = runTest {
        coEvery { transactions.getRecognisedToControl("2026-10") } returns Result.failure(RuntimeException("boom"))
        val viewModel = load()
        advanceUntilIdle()

        assertEquals("boom", viewModel.uiState.value.loadError)
        assertFalse(viewModel.uiState.value.loaded)
        assertFalse(viewModel.uiState.value.isEmpty)

        coEvery { transactions.getRecognisedToControl("2026-10") } returns Result.success(data)
        viewModel.refresh()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.loadError)
        assertTrue(viewModel.uiState.value.loaded)
        assertEquals(2, viewModel.uiState.value.data.groups.size)
    }

    @Test
    fun `validating a group checks all its lines in one call and the group leaves`() = runTest {
        coEvery { transactions.controlRecognised(listOf("t1", "t2", "t3"), false) } returns Result.success(ControlResult(3, 0))
        val viewModel = load()
        advanceUntilIdle()

        viewModel.controlGroup(groceries.key, keep = false)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf("Salaire"), state.data.groups.map { it.label })
        assertEquals(1, state.data.total)
        assertNull(state.controllingKey)
        assertEquals("Courses : 3 lignes validées", state.message)
        coVerify(exactly = 1) { transactions.controlRecognised(listOf("t1", "t2", "t3"), false) }
    }

    @Test
    fun `tout a conserver judges the spends and says how many`() = runTest {
        coEvery { transactions.controlRecognised(listOf("t1", "t2", "t3"), true) } returns Result.success(ControlResult(3, 3))
        val viewModel = load()
        advanceUntilIdle()

        viewModel.controlGroup(groceries.key, keep = true)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf("Salaire"), state.data.groups.map { it.label })
        assertEquals("Courses : 3 lignes validées · 3 jugées à conserver", state.message)
        coVerify(exactly = 1) { transactions.controlRecognised(listOf("t1", "t2", "t3"), true) }
    }

    @Test
    fun `a refused check keeps the group and its lines where they were and says so`() = runTest {
        coEvery { transactions.controlRecognised(any(), any()) } returns Result.failure(RuntimeException("unknown line"))
        val viewModel = load()
        advanceUntilIdle()

        viewModel.controlGroup(groceries.key, keep = false)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(2, state.data.groups.size)
        assertEquals(3, state.data.groups.first().transactions.size)
        assertEquals("unknown line", state.actionError)
        assertNull(state.controllingKey)
        assertNull(state.message)

        viewModel.clearActionError()
        assertNull(viewModel.uiState.value.actionError)
    }

    @Test
    fun `a second check while one is in flight is not sent`() = runTest {
        coEvery { transactions.controlRecognised(any(), any()) } returns Result.success(ControlResult(3, 0))
        val viewModel = load()
        advanceUntilIdle()

        viewModel.controlGroup(groceries.key, keep = false)
        viewModel.controlGroup(salary.key, keep = false)
        advanceUntilIdle()

        coVerify(exactly = 1) { transactions.controlRecognised(any(), any()) }
    }

    @Test
    fun `judging a line sets its verdict and counts it in its group, and the line stays`() = runTest {
        coEvery { transactions.setRetrospect("t2", RETROSPECT_AVOIDABLE) } returns Result.success(Unit)
        val viewModel = load()
        advanceUntilIdle()

        viewModel.judge("t2", RETROSPECT_AVOIDABLE)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        val group = state.data.groups.first()
        assertEquals(RETROSPECT_AVOIDABLE, group.transactions.first { it.id == "t2" }.retrospect)
        assertEquals(3, group.transactions.size)
        assertEquals(2, group.unratedCount)
        assertNull(state.judgingId)
        assertNull(state.actionError)
    }

    @Test
    fun `tapping the verdict a line already has takes it back`() = runTest {
        coEvery { transactions.getRecognisedToControl("2026-10") } returns Result.success(
            data.copy(
                groups = listOf(
                    groceries.copy(
                        unratedCount = 2,
                        transactions = listOf(line("t1", retrospect = RETROSPECT_KEEP)) + groceries.transactions.drop(1),
                    ),
                    salary,
                ),
            ),
        )
        coEvery { transactions.setRetrospect("t1", RETROSPECT_UNRATED) } returns Result.success(Unit)
        val viewModel = load()
        advanceUntilIdle()

        viewModel.judge("t1", RETROSPECT_KEEP)
        advanceUntilIdle()

        val group = viewModel.uiState.value.data.groups.first()
        assertEquals(RETROSPECT_UNRATED, group.transactions.first().retrospect)
        assertEquals(3, group.unratedCount)
        coVerify(exactly = 1) { transactions.setRetrospect("t1", RETROSPECT_UNRATED) }
    }

    @Test
    fun `a line that cannot be judged is never sent a verdict`() = runTest {
        val viewModel = load()
        advanceUntilIdle()

        viewModel.judge("t4", RETROSPECT_KEEP)
        advanceUntilIdle()

        coVerify(exactly = 0) { transactions.setRetrospect(any(), any()) }
        assertNull(viewModel.uiState.value.judgingId)
    }

    @Test
    fun `a refused verdict leaves the line with the one it had`() = runTest {
        coEvery { transactions.setRetrospect(any(), any()) } returns Result.failure(RuntimeException("Only a spend can be judged."))
        val viewModel = load()
        advanceUntilIdle()

        viewModel.judge("t1", RETROSPECT_KEEP)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(RETROSPECT_UNRATED, state.data.groups.first().transactions.first().retrospect)
        assertEquals("Only a spend can be judged.", state.actionError)
        assertNull(state.judgingId)
    }

    @Test
    fun `correcting opens on the line's category with the qualification the operation set`() = runTest {
        coEvery { transactions.getTransaction("t1") } returns Result.success(
            Transaction(id = "t1", label = "CARREFOUR t1", qualification = Qualification(true, QualificationKind.Saving)),
        )
        val viewModel = load()
        advanceUntilIdle()

        viewModel.startCorrection("t1")

        // Until the qualification is read, saving would overwrite it with nothing.
        assertTrue(viewModel.uiState.value.correction!!.isLoading)
        assertFalse(viewModel.uiState.value.correction!!.canSave)

        advanceUntilIdle()

        val correction = viewModel.uiState.value.correction!!
        assertEquals("t1", correction.line.id)
        assertEquals("cat-courses", correction.categoryId)
        assertEquals(Qualification(true, QualificationKind.Saving), correction.qualification)
        assertFalse(correction.isLoading)
        assertTrue(correction.canSave)
    }

    @Test
    fun `a qualification that cannot be read does not open the correction`() = runTest {
        coEvery { transactions.getTransaction("t1") } returns Result.failure(RuntimeException("offline"))
        val viewModel = load()
        advanceUntilIdle()

        viewModel.startCorrection("t1")
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.correction)
        assertEquals("offline", viewModel.uiState.value.actionError)
        assertEquals(3, viewModel.uiState.value.data.groups.first().transactions.size)
    }

    @Test
    fun `saving a correction files the line by hand with the whole qualification and it leaves the list`() = runTest {
        val qualification = Qualification(true, QualificationKind.None)
        coEvery { transactions.getTransaction("t2") } returns Result.success(Transaction(id = "t2", label = "x"))
        coEvery { transactions.setQualification("t2", "cat-loisirs", qualification) } returns
            Result.success(Transaction(id = "t2", label = "x", qualifiedBy = "manual"))
        val viewModel = load()
        advanceUntilIdle()
        viewModel.startCorrection("t2")
        advanceUntilIdle()

        viewModel.setCorrectionCategory("cat-loisirs")
        viewModel.setCorrectionQualification(qualification)
        viewModel.saveCorrection()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNull(state.correction)
        val group = state.data.groups.first()
        assertEquals(listOf("t1", "t3"), group.transactions.map { it.id })
        assertEquals(2, group.count)
        assertEquals(-4000, group.totalCents)
        assertEquals(2, group.unratedCount)
        assertEquals(3, state.data.total)
        assertEquals("Ligne corrigée", state.message)
        coVerify(exactly = 1) { transactions.setQualification("t2", "cat-loisirs", qualification) }
    }

    @Test
    fun `correcting the last line of a group takes the group away`() = runTest {
        coEvery { transactions.getTransaction("t4") } returns Result.success(Transaction(id = "t4", label = "x"))
        coEvery { transactions.setQualification(any(), any(), any()) } returns Result.success(Transaction(id = "t4", label = "x"))
        val viewModel = load()
        advanceUntilIdle()
        viewModel.startCorrection("t4")
        advanceUntilIdle()

        viewModel.saveCorrection()
        advanceUntilIdle()

        assertEquals(listOf("Courses"), viewModel.uiState.value.data.groups.map { it.label })
        assertEquals(3, viewModel.uiState.value.data.total)
    }

    @Test
    fun `a refused correction stays open with the reason and the line stays in the list`() = runTest {
        coEvery { transactions.getTransaction("t1") } returns Result.success(Transaction(id = "t1", label = "x"))
        coEvery { transactions.setQualification(any(), any(), any()) } returns Result.failure(RuntimeException("Qualification refused"))
        val viewModel = load()
        advanceUntilIdle()
        viewModel.startCorrection("t1")
        advanceUntilIdle()

        viewModel.saveCorrection()
        advanceUntilIdle()

        val correction = viewModel.uiState.value.correction
        assertNotNull(correction)
        assertEquals("Qualification refused", correction!!.error)
        assertFalse(correction.isSaving)
        assertEquals(3, viewModel.uiState.value.data.groups.first().transactions.size)
        assertEquals(4, viewModel.uiState.value.data.total)
    }

    @Test
    fun `a correction without category cannot be saved, and can be cancelled`() = runTest {
        coEvery { transactions.getTransaction("t1") } returns Result.success(Transaction(id = "t1", label = "x"))
        val viewModel = load()
        advanceUntilIdle()
        viewModel.startCorrection("t1")
        advanceUntilIdle()

        viewModel.setCorrectionCategory(null)
        viewModel.saveCorrection()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.correction!!.canSave)
        coVerify(exactly = 0) { transactions.setQualification(any(), any(), any()) }

        viewModel.cancelCorrection()

        assertNull(viewModel.uiState.value.correction)
        assertEquals(4, viewModel.uiState.value.data.total)
    }
}

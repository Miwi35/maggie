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

    @Test
    fun `the page opens on the list, with nothing selected and no fiche`() = runTest {
        val viewModel = load()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.ficheOpen)
        assertNull(state.selectedId)
        assertNull(state.current)
        assertEquals(listOf("t1", "t2", "t3", "t4"), state.flatLines.map { it.id })
        assertEquals(4, state.total)
        assertEquals(0, state.position)
    }

    @Test
    fun `selecting a line opens its fiche at its place in the flat order`() = runTest {
        val viewModel = load()
        advanceUntilIdle()

        viewModel.select("t3")

        val state = viewModel.uiState.value
        assertTrue(state.ficheOpen)
        assertEquals("t3", state.selectedId)
        assertEquals("t3", state.current?.id)
        assertEquals("Courses", state.currentGroup?.label)
        assertEquals(3, state.position)
        assertTrue(state.hasPrevious)
        assertTrue(state.hasNext)
    }

    @Test
    fun `selecting a line that is not in the list does nothing`() = runTest {
        val viewModel = load()
        advanceUntilIdle()

        viewModel.select("nope")

        assertFalse(viewModel.uiState.value.ficheOpen)
        assertNull(viewModel.uiState.value.selectedId)
    }

    @Test
    fun `next and previous walk the groups flattened in order, across a group boundary`() = runTest {
        val viewModel = load()
        advanceUntilIdle()
        viewModel.select("t2")

        viewModel.next()
        assertEquals("t3", viewModel.uiState.value.selectedId)
        viewModel.next()
        assertEquals("t4", viewModel.uiState.value.selectedId)
        assertEquals("Salaire", viewModel.uiState.value.currentGroup?.label)
        viewModel.previous()
        viewModel.previous()
        assertEquals("t2", viewModel.uiState.value.selectedId)
        assertTrue(viewModel.uiState.value.ficheOpen)
    }

    @Test
    fun `next and previous do nothing at the ends of the list`() = runTest {
        val viewModel = load()
        advanceUntilIdle()

        viewModel.select("t4")
        assertFalse(viewModel.uiState.value.hasNext)
        viewModel.next()
        assertEquals("t4", viewModel.uiState.value.selectedId)

        viewModel.select("t1")
        assertFalse(viewModel.uiState.value.hasPrevious)
        viewModel.previous()
        assertEquals("t1", viewModel.uiState.value.selectedId)
    }

    @Test
    fun `next and previous do nothing while no line is selected`() = runTest {
        val viewModel = load()
        advanceUntilIdle()

        viewModel.next()
        viewModel.previous()

        assertNull(viewModel.uiState.value.selectedId)
        assertFalse(viewModel.uiState.value.ficheOpen)
    }

    @Test
    fun `back to the list closes the fiche and keeps the line last open`() = runTest {
        val viewModel = load()
        advanceUntilIdle()
        viewModel.select("t1")
        viewModel.next()

        viewModel.backToList()

        val state = viewModel.uiState.value
        assertFalse(state.ficheOpen)
        assertEquals("t2", state.selectedId)
        assertEquals(2, state.position)
    }

    @Test
    fun `correcting the open line removes it and the fiche shows the one that followed`() = runTest {
        coEvery { transactions.getTransaction("t2") } returns Result.success(Transaction(id = "t2", label = "x"))
        coEvery { transactions.setQualification(any(), any(), any()) } returns Result.success(Transaction(id = "t2", label = "x"))
        val viewModel = load()
        advanceUntilIdle()
        viewModel.select("t2")
        viewModel.startCorrection("t2")
        advanceUntilIdle()

        viewModel.saveCorrection()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf("t1", "t3", "t4"), state.flatLines.map { it.id })
        assertEquals("t3", state.selectedId)
        assertTrue(state.ficheOpen)
        assertEquals(2, state.position)
        assertEquals(3, state.total)
    }

    @Test
    fun `correcting the last line of the list moves the fiche to the one before`() = runTest {
        coEvery { transactions.getTransaction("t4") } returns Result.success(Transaction(id = "t4", label = "x"))
        coEvery { transactions.setQualification(any(), any(), any()) } returns Result.success(Transaction(id = "t4", label = "x"))
        val viewModel = load()
        advanceUntilIdle()
        viewModel.select("t4")
        viewModel.startCorrection("t4")
        advanceUntilIdle()

        viewModel.saveCorrection()
        advanceUntilIdle()

        assertEquals("t3", viewModel.uiState.value.selectedId)
        assertTrue(viewModel.uiState.value.ficheOpen)
    }

    @Test
    fun `correcting another line than the selected one leaves the cursor alone`() = runTest {
        coEvery { transactions.getTransaction("t1") } returns Result.success(Transaction(id = "t1", label = "x"))
        coEvery { transactions.setQualification(any(), any(), any()) } returns Result.success(Transaction(id = "t1", label = "x"))
        val viewModel = load()
        advanceUntilIdle()
        viewModel.select("t3")
        viewModel.startCorrection("t1")
        advanceUntilIdle()

        viewModel.saveCorrection()
        advanceUntilIdle()

        assertEquals("t3", viewModel.uiState.value.selectedId)
        assertEquals(2, viewModel.uiState.value.position)
    }

    @Test
    fun `a refused correction keeps the fiche on its line`() = runTest {
        coEvery { transactions.getTransaction("t2") } returns Result.success(Transaction(id = "t2", label = "x"))
        coEvery { transactions.setQualification(any(), any(), any()) } returns Result.failure(RuntimeException("refused"))
        val viewModel = load()
        advanceUntilIdle()
        viewModel.select("t2")
        viewModel.startCorrection("t2")
        advanceUntilIdle()

        viewModel.saveCorrection()
        advanceUntilIdle()
        viewModel.cancelCorrection()

        assertEquals("t2", viewModel.uiState.value.selectedId)
        assertTrue(viewModel.uiState.value.ficheOpen)
        assertEquals(4, viewModel.uiState.value.total)
    }

    @Test
    fun `controlling the group of the open line moves the cursor to the next line, still open`() = runTest {
        coEvery { transactions.controlRecognised(any(), any()) } returns Result.success(ControlResult(3, 0))
        val viewModel = load()
        advanceUntilIdle()
        viewModel.select("t2")

        viewModel.controlGroup(groceries.key, keep = false)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("t4", state.selectedId)
        assertTrue(state.ficheOpen)
        assertEquals(1, state.position)
    }

    @Test
    fun `controlling another group leaves the cursor on its line`() = runTest {
        coEvery { transactions.controlRecognised(any(), any()) } returns Result.success(ControlResult(1, 0))
        val viewModel = load()
        advanceUntilIdle()
        viewModel.select("t2")

        viewModel.controlGroup(salary.key, keep = false)
        advanceUntilIdle()

        assertEquals("t2", viewModel.uiState.value.selectedId)
        assertEquals(2, viewModel.uiState.value.position)
    }

    @Test
    fun `controlling the group while on the list moves the highlight but does not open a fiche`() = runTest {
        coEvery { transactions.controlRecognised(any(), any()) } returns Result.success(ControlResult(3, 0))
        val viewModel = load()
        advanceUntilIdle()
        viewModel.select("t1")
        viewModel.backToList()

        viewModel.controlGroup(groceries.key, keep = false)
        advanceUntilIdle()

        assertEquals("t4", viewModel.uiState.value.selectedId)
        assertFalse(viewModel.uiState.value.ficheOpen)
    }

    @Test
    fun `when the last line leaves, nothing is selected and the fiche closes`() = runTest {
        coEvery { transactions.getRecognisedToControl("2026-10") } returns Result.success(
            RecognisedToControl(month = "2026-10", total = 1, groups = listOf(salary)),
        )
        coEvery { transactions.controlRecognised(listOf("t4"), false) } returns Result.success(ControlResult(1, 0))
        val viewModel = load()
        advanceUntilIdle()
        viewModel.select("t4")

        viewModel.controlGroup(salary.key, keep = false)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNull(state.selectedId)
        assertFalse(state.ficheOpen)
        assertTrue(state.isEmpty)
        assertEquals(0, state.position)
    }

    @Test
    fun `judging a line keeps the cursor and the fiche where they are`() = runTest {
        coEvery { transactions.setRetrospect("t2", RETROSPECT_KEEP) } returns Result.success(Unit)
        val viewModel = load()
        advanceUntilIdle()
        viewModel.select("t2")

        viewModel.judge("t2", RETROSPECT_KEEP)
        advanceUntilIdle()

        assertEquals("t2", viewModel.uiState.value.selectedId)
        assertTrue(viewModel.uiState.value.ficheOpen)
        assertEquals(RETROSPECT_KEEP, viewModel.uiState.value.current?.retrospect)
    }
}

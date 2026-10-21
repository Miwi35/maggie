package com.maggie.app.ui.screens.finance

import com.maggie.app.data.api.ApiRefusalException
import com.maggie.app.data.api.CategorizationRuleCreateRequest
import com.maggie.app.data.model.Category
import com.maggie.app.data.model.CategorizationRule
import com.maggie.app.data.model.Contact
import com.maggie.app.data.model.ContactCounterparty
import com.maggie.app.data.model.ContactRef
import com.maggie.app.data.model.ControlResult
import com.maggie.app.data.model.OPERATION_RULE
import com.maggie.app.data.model.PeerAllocationCandidate
import com.maggie.app.data.model.PeerAllocationRequest
import com.maggie.app.data.model.PeerAllocationRequestLine
import com.maggie.app.data.model.PeerKind
import com.maggie.app.data.model.Qualification
import com.maggie.app.data.model.QualificationKind
import com.maggie.app.data.model.RETROSPECT_AVOIDABLE
import com.maggie.app.data.model.RETROSPECT_KEEP
import com.maggie.app.data.model.RETROSPECT_UNRATED
import com.maggie.app.data.model.ReviewPeriod
import com.maggie.app.data.model.ReviewPreset
import com.maggie.app.data.model.ReviewQueue
import com.maggie.app.data.model.ReviewQueueItem
import com.maggie.app.data.model.ReviewState
import com.maggie.app.data.model.RulePreview
import com.maggie.app.data.model.SortResult
import com.maggie.app.data.model.SortSuggestion
import com.maggie.app.data.model.SortedRule
import com.maggie.app.data.model.TO_SORT_REASON_CATEGORY
import com.maggie.app.data.model.TO_SORT_REASON_PEER_TRANSFER
import com.maggie.app.data.model.Transaction
import com.maggie.app.data.model.TransactionAllocation
import com.maggie.app.data.model.TransferLeg
import com.maggie.app.data.repository.CategorizationRuleRepository
import com.maggie.app.data.repository.CategoryRepository
import com.maggie.app.data.repository.ContactRepository
import com.maggie.app.data.repository.PeerDebtRepository
import com.maggie.app.data.repository.ReviewRepository
import com.maggie.app.data.repository.TransactionRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
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

@OptIn(ExperimentalCoroutinesApi::class)
class ReviewListViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var reviews: ReviewRepository
    private lateinit var transactions: TransactionRepository
    private lateinit var categories: CategoryRepository
    private lateinit var rules: CategorizationRuleRepository
    private lateinit var contacts: ContactRepository
    private lateinit var peerDebts: PeerDebtRepository

    // Two lines to qualify (the older with a suggestion, the other judgeable) and one recognised by a rule.
    private val carrefour = ReviewQueueItem(
        id = "tx-1",
        label = "CARREFOUR MARKET 1234",
        amountCents = -5320,
        bookedAt = "2026-10-05",
        accountName = "Courant",
        suggestion = SortSuggestion("cat-courses", "Alimentation › Courses", "rule", "Règle « contient CARREFOUR »"),
        counterpartyLabel = "CARREFOUR MARKET 1234",
    )

    private val unknown = ReviewQueueItem(
        id = "tx-2",
        label = "DUPONT MARTIN",
        amountCents = -1200,
        bookedAt = "2026-10-11",
        judgeable = true,
        asksForContact = true,
        counterpartyLabel = "DUPONT MARTIN",
    )

    private val netflix = ReviewQueueItem(
        id = "tx-4",
        label = "NETFLIX.COM",
        recognised = true,
        amountCents = -1399,
        bookedAt = "2026-10-08",
        judgeable = true,
        categoryId = "cat-loisirs",
        categoryPath = "Loisirs",
        qualifiedBy = OPERATION_RULE,
        qualifiedByRuleId = "rule-1",
        operationLabel = "Abonnement vidéo",
    )

    private val mandatorySaving = Qualification(mandatory = true, kind = QualificationKind.Saving)

    private val tom = Contact(id = "c-tom", name = "Tom")

    private val categoryList = listOf(
        Category(id = "cat-food", name = "Alimentation"),
        Category(id = "cat-courses", name = "Courses", parent = "/api/categories/cat-food"),
        Category(id = "cat-loisirs", name = "Loisirs"),
    )

    private fun queue(vararg items: ReviewQueueItem, sinceReviewDate: String? = null, month: String = "2026-10") = ReviewQueue(
        month = month,
        unrecognisedCount = items.count { !it.recognised },
        unrecognisedCents = items.filter { !it.recognised }.sumOf { kotlin.math.abs(it.amountCents) },
        recognisedCount = items.count { it.recognised },
        sinceReviewDate = sinceReviewDate,
        total = items.size,
        items = items.toList(),
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        reviews = mockk()
        transactions = mockk()
        categories = mockk()
        rules = mockk()
        contacts = mockk()
        peerDebts = mockk()
        coEvery { contacts.getContacts() } returns Result.success(listOf(tom))
        coEvery { categories.getCategories() } returns Result.success(categoryList)
        coEvery { reviews.getList(any(), any(), any(), any()) } returns Result.success(queue(carrefour, unknown, netflix))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun build() = ReviewListViewModel(
        reviews, transactions, categories, rules, contacts, peerDebts,
        today = LocalDate.of(2026, 10, 16),
    )

    /** The list opened on its defaults and read. */
    private fun TestScope.load(): ReviewListViewModel {
        val viewModel = build()
        viewModel.open(null)
        advanceUntilIdle()
        return viewModel
    }

    /** The list read, the fiche of [id] open. */
    private fun TestScope.loadFiche(id: String): ReviewListViewModel {
        val viewModel = load()
        viewModel.select(id)
        return viewModel
    }

    // Loading

    @Test
    fun `the list is loading until the server has answered, then shows the month, the counts and the lines in order`() = runTest {
        val viewModel = build()
        viewModel.open(null)

        assertTrue(viewModel.uiState.value.isLoading)
        assertFalse(viewModel.uiState.value.loaded)

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertTrue(state.loaded)
        assertEquals(ReviewState.ALL, state.state)
        assertEquals(ReviewPeriod.MONTH, state.period)
        assertEquals("2026-10", state.month)
        assertEquals(3, state.counts.all)
        assertEquals(2, state.counts.unrecognised)
        assertEquals(6520, state.counts.unrecognisedCents)
        assertEquals(1, state.counts.recognised)
        // Lines to qualify first, then the recognised, each by ascending date.
        assertEquals(listOf("tx-1", "tx-2", "tx-4"), state.visibleIds)
        assertEquals(listOf("tx-1", "tx-2"), state.unrecognisedRows.map { it.id })
        assertEquals(listOf("tx-4"), state.recognisedRows.map { it.id })
        assertEquals(listOf("cat-food", "cat-courses", "cat-loisirs"), state.categories.map { it.id })
        coVerify(exactly = 1) { reviews.getList(ReviewPeriod.MONTH, "2026-10", any(), any()) }
    }

    @Test
    fun `a period that cannot be read says so, and the retry reads it again`() = runTest {
        coEvery { reviews.getList(any(), any(), any(), any()) } returnsMany listOf(
            Result.failure(RuntimeException("offline")),
            Result.success(queue(carrefour)),
        )
        val viewModel = load()

        assertEquals("offline", viewModel.uiState.value.loadError)
        assertFalse(viewModel.uiState.value.isLoading)
        assertFalse(viewModel.uiState.value.loaded)

        viewModel.refresh()
        assertTrue(viewModel.uiState.value.isLoading)
        assertNull(viewModel.uiState.value.loadError)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.loaded)
        assertEquals(listOf("tx-1"), viewModel.uiState.value.visibleIds)
    }

    @Test
    fun `an empty period is empty only once it has been read`() = runTest {
        coEvery { reviews.getList(any(), any(), any(), any()) } returns Result.success(queue())
        val viewModel = build()
        viewModel.open(null)

        assertFalse(viewModel.uiState.value.isEmpty)

        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isEmpty)
    }

    @Test
    fun `opening without a preset loads the defaults once`() = runTest {
        val viewModel = load()

        viewModel.open(null)
        advanceUntilIdle()

        coVerify(exactly = 1) { reviews.getList(any(), any(), any(), any()) }
    }

    // Filters

    @Test
    fun `a state pill filters the lines already read, with no second request, and the counts stay the period's`() = runTest {
        val viewModel = load()

        viewModel.setState(ReviewState.UNRECOGNISED)

        var state = viewModel.uiState.value
        assertEquals(ReviewState.UNRECOGNISED, state.state)
        assertEquals(listOf("tx-1", "tx-2"), state.visibleIds)
        assertEquals(3, state.counts.all)

        viewModel.setState(ReviewState.RECOGNISED)

        state = viewModel.uiState.value
        assertEquals(listOf("tx-4"), state.visibleIds)
        assertEquals(1, state.total)
        assertEquals(2, state.counts.unrecognised)

        viewModel.setState(ReviewState.ALL)

        assertEquals(3, viewModel.uiState.value.total)
        coVerify(exactly = 1) { reviews.getList(any(), any(), any(), any()) }
    }

    @Test
    fun `another period is read again, from the same month`() = runTest {
        val viewModel = load()

        viewModel.setPeriod(ReviewPeriod.SINCE_REVIEW)
        assertTrue(viewModel.uiState.value.isLoading)
        advanceUntilIdle()

        assertEquals(ReviewPeriod.SINCE_REVIEW, viewModel.uiState.value.period)
        coVerify(exactly = 1) { reviews.getList(ReviewPeriod.SINCE_REVIEW, "2026-10", any(), any()) }

        viewModel.setPeriod(ReviewPeriod.ALL)
        advanceUntilIdle()

        coVerify(exactly = 1) { reviews.getList(ReviewPeriod.ALL, "2026-10", any(), any()) }
    }

    @Test
    fun `the same period is not read twice`() = runTest {
        val viewModel = load()

        viewModel.setPeriod(ReviewPeriod.MONTH)
        advanceUntilIdle()

        coVerify(exactly = 1) { reviews.getList(any(), any(), any(), any()) }
    }

    @Test
    fun `the month selector goes back and forth month by month`() = runTest {
        val viewModel = load()

        viewModel.previousMonth()
        advanceUntilIdle()
        assertEquals("2026-09", viewModel.uiState.value.month)

        viewModel.nextMonth()
        advanceUntilIdle()
        viewModel.nextMonth()
        advanceUntilIdle()
        assertEquals("2026-11", viewModel.uiState.value.month)

        coVerify(exactly = 1) { reviews.getList(ReviewPeriod.MONTH, "2026-09", any(), any()) }
        coVerify(exactly = 1) { reviews.getList(ReviewPeriod.MONTH, "2026-11", any(), any()) }
        coVerify(exactly = 2) { reviews.getList(ReviewPeriod.MONTH, "2026-10", any(), any()) }
    }

    @Test
    fun `picking a month while on another period comes back to the month`() = runTest {
        val viewModel = load()
        viewModel.setPeriod(ReviewPeriod.ALL)
        advanceUntilIdle()

        viewModel.setMonth("2026-03")
        advanceUntilIdle()

        assertEquals(ReviewPeriod.MONTH, viewModel.uiState.value.period)
        assertEquals("2026-03", viewModel.uiState.value.month)
        coVerify(exactly = 1) { reviews.getList(ReviewPeriod.MONTH, "2026-03", any(), any()) }
    }

    @Test
    fun `a read that comes back late does not replace the one asked after it`() = runTest {
        val september = queue(unknown, month = "2026-09")
        coEvery { reviews.getList(ReviewPeriod.MONTH, "2026-10", any(), any()) } coAnswers {
            delay(100)
            Result.success(queue(carrefour, unknown, netflix))
        }
        coEvery { reviews.getList(ReviewPeriod.MONTH, "2026-09", any(), any()) } returns Result.success(september)
        val viewModel = build()
        viewModel.open(null)
        viewModel.previousMonth()
        advanceUntilIdle()

        assertEquals("2026-09", viewModel.uiState.value.month)
        assertEquals(listOf("tx-2"), viewModel.uiState.value.visibleIds)
        assertFalse(viewModel.uiState.value.isLoading)
    }

    // Deep-link presets

    @Test
    fun `the to-sort link opens on À qualifier and every date`() = runTest {
        val viewModel = build()

        viewModel.open(ReviewPreset.TO_SORT)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(ReviewState.UNRECOGNISED, state.state)
        assertEquals(ReviewPeriod.ALL, state.period)
        assertEquals(listOf("tx-1", "tx-2"), state.visibleIds)
        coVerify(exactly = 1) { reviews.getList(ReviewPeriod.ALL, "2026-10", any(), any()) }
    }

    @Test
    fun `the to-control link opens on Reconnues since the last review`() = runTest {
        val viewModel = build()

        viewModel.open(ReviewPreset.TO_CONTROL)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(ReviewState.RECOGNISED, state.state)
        assertEquals(ReviewPeriod.SINCE_REVIEW, state.period)
        assertEquals(listOf("tx-4"), state.visibleIds)
        coVerify(exactly = 1) { reviews.getList(ReviewPeriod.SINCE_REVIEW, "2026-10", any(), any()) }
    }

    @Test
    fun `the monthly review link opens on Toutes and the current month`() = runTest {
        val viewModel = build()

        viewModel.open(ReviewPreset.MONTHLY)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(ReviewState.ALL, state.state)
        assertEquals(ReviewPeriod.MONTH, state.period)
        assertEquals("2026-10", state.month)
        assertEquals(3, state.total)
    }

    @Test
    fun `a preset with a month reads that month`() = runTest {
        val viewModel = build()

        viewModel.open(ReviewPreset(ReviewState.ALL, ReviewPeriod.MONTH, "2026-07"))
        advanceUntilIdle()

        assertEquals("2026-07", viewModel.uiState.value.month)
        coVerify(exactly = 1) { reviews.getList(ReviewPeriod.MONTH, "2026-07", any(), any()) }
    }

    @Test
    fun `the same preset again, a rotation or the way back, keeps the filters the owner changed`() = runTest {
        val viewModel = build()
        viewModel.open(ReviewPreset.TO_SORT)
        advanceUntilIdle()
        viewModel.setState(ReviewState.ALL)

        viewModel.open(ReviewPreset.TO_SORT)
        advanceUntilIdle()

        assertEquals(ReviewState.ALL, viewModel.uiState.value.state)
        coVerify(exactly = 1) { reviews.getList(any(), any(), any(), any()) }
    }

    @Test
    fun `a new preset replaces the filters and closes the fiche`() = runTest {
        val viewModel = build()
        viewModel.open(ReviewPreset.MONTHLY)
        advanceUntilIdle()
        viewModel.select("tx-1")

        viewModel.open(ReviewPreset.TO_CONTROL)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(ReviewState.RECOGNISED, state.state)
        assertFalse(state.ficheOpen)
        assertNull(state.selectedId)
    }

    // The fiche

    @Test
    fun `opening a line opens its fiche at its place in the filter`() = runTest {
        val viewModel = load()
        viewModel.setState(ReviewState.UNRECOGNISED)

        viewModel.select("tx-2")

        val state = viewModel.uiState.value
        assertTrue(state.ficheOpen)
        assertEquals("tx-2", state.current?.id)
        assertEquals(2, state.position)
        assertEquals(2, state.total)
    }

    @Test
    fun `a line the filter does not keep cannot be opened`() = runTest {
        val viewModel = load()
        viewModel.setState(ReviewState.RECOGNISED)

        viewModel.select("tx-1")

        assertFalse(viewModel.uiState.value.ficheOpen)
        assertNull(viewModel.uiState.value.current)
    }

    @Test
    fun `Suivant and Précédent walk the filter and stop at its ends`() = runTest {
        val viewModel = loadFiche("tx-1")
        assertEquals(1, viewModel.uiState.value.position)
        assertFalse(viewModel.uiState.value.hasPrevious)
        assertTrue(viewModel.uiState.value.hasNext)

        viewModel.previous()
        assertEquals("tx-1", viewModel.uiState.value.current?.id)

        viewModel.next()
        assertEquals("tx-2", viewModel.uiState.value.current?.id)
        assertEquals(2, viewModel.uiState.value.position)

        viewModel.next()
        assertEquals("tx-4", viewModel.uiState.value.current?.id)
        assertEquals(3, viewModel.uiState.value.position)
        assertFalse(viewModel.uiState.value.hasNext)

        viewModel.next()
        assertEquals("tx-4", viewModel.uiState.value.current?.id)

        viewModel.previous()
        assertEquals("tx-2", viewModel.uiState.value.current?.id)
    }

    @Test
    fun `the way back to the list closes the fiche and keeps the filters and the line the owner was on`() = runTest {
        val viewModel = load()
        viewModel.setState(ReviewState.UNRECOGNISED)
        viewModel.select("tx-2")

        viewModel.backToList()

        val state = viewModel.uiState.value
        assertFalse(state.ficheOpen)
        assertEquals(ReviewState.UNRECOGNISED, state.state)
        assertEquals("tx-2", state.selectedId)
    }

    @Test
    fun `the cursor survives a state pill that still keeps its line and leaves with one that does not`() = runTest {
        val viewModel = load()
        viewModel.select("tx-1")

        viewModel.setState(ReviewState.UNRECOGNISED)
        assertEquals("tx-1", viewModel.uiState.value.selectedId)

        viewModel.setState(ReviewState.RECOGNISED)
        assertNull(viewModel.uiState.value.selectedId)
    }

    // Valider

    @Test
    fun `validating a line to qualify files it under the suggestion, it becomes Reconnue in place and the fiche stays on it`() = runTest {
        coEvery { transactions.sortToCategory("tx-1", "cat-courses", false) } returns Result.success(SortResult(success = true, remaining = 1))
        val viewModel = loadFiche("tx-1")
        assertTrue(viewModel.uiState.value.canValidate)

        viewModel.validate()
        assertTrue(viewModel.uiState.value.isSorting)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isSorting)
        val line = state.current!!
        assertEquals("tx-1", line.id)
        assertTrue(line.recognised)
        assertEquals("cat-courses", line.categoryId)
        assertEquals("Alimentation › Courses", line.categoryPath)
        assertTrue(state.ficheOpen)
        // The pills follow at once: one line to qualify less, one recognised more, the weight lowered.
        assertEquals(1, state.counts.unrecognised)
        assertEquals(1200, state.counts.unrecognisedCents)
        assertEquals(2, state.counts.recognised)
        assertEquals(3, state.counts.all)
        assertFalse(state.canValidate)
        coVerify(exactly = 1) { transactions.sortToCategory("tx-1", "cat-courses", false) }
        coVerify(exactly = 1) { reviews.getList(any(), any(), any(), any()) }
    }

    @Test
    fun `on À qualifier a validated line stays for the fiche, Suivant goes to the next line, and the list drops it`() = runTest {
        coEvery { transactions.sortToCategory("tx-1", "cat-courses", false) } returns Result.success(SortResult(success = true))
        val viewModel = load()
        viewModel.setState(ReviewState.UNRECOGNISED)
        viewModel.select("tx-1")

        viewModel.validate()
        advanceUntilIdle()

        // The fiche keeps its order: the line is still 1 / 2, Reconnue.
        assertEquals(listOf("tx-1", "tx-2"), viewModel.uiState.value.visibleIds)
        assertEquals("1", viewModel.uiState.value.position.toString())
        assertTrue(viewModel.uiState.value.current!!.recognised)
        assertEquals(1, viewModel.uiState.value.counts.unrecognised)

        viewModel.next()

        assertEquals("tx-2", viewModel.uiState.value.current?.id)
        assertFalse(viewModel.uiState.value.current!!.recognised)

        viewModel.backToList()

        assertEquals(listOf("tx-2"), viewModel.uiState.value.visibleIds)
        assertEquals(listOf("tx-2"), viewModel.uiState.value.unrecognisedRows.map { it.id })
    }

    @Test
    fun `a line without suggestion cannot be validated`() = runTest {
        val viewModel = loadFiche("tx-2")

        assertFalse(viewModel.uiState.value.canValidate)
        viewModel.validate()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isSorting)
        assertFalse(viewModel.uiState.value.current!!.recognised)
        coVerify(exactly = 0) { transactions.sortToCategory(any(), any(), any()) }
    }

    @Test
    fun `a second tap on Valider while the first is in flight sends one answer`() = runTest {
        coEvery { transactions.sortToCategory("tx-1", "cat-courses", false) } returns Result.success(SortResult(success = true))
        val viewModel = loadFiche("tx-1")

        viewModel.validate()
        viewModel.validate()
        advanceUntilIdle()

        coVerify(exactly = 1) { transactions.sortToCategory(any(), any(), any()) }
    }

    @Test
    fun `a refused answer leaves the line to qualify, with the reason`() = runTest {
        coEvery { transactions.sortToCategory("tx-1", "cat-courses", false) } returns Result.failure(RuntimeException("refused"))
        val viewModel = loadFiche("tx-1")

        viewModel.validate()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("refused", state.sortError)
        assertFalse(state.isSorting)
        assertFalse(state.current!!.recognised)
        assertEquals(2, state.counts.unrecognised)

        viewModel.clearSortError()

        assertNull(viewModel.uiState.value.sortError)
    }

    @Test
    fun `Autre catégorie files the line under the heading picked in the search`() = runTest {
        coEvery { transactions.sortToCategory("tx-2", "cat-food", false) } returns Result.success(SortResult(success = true))
        val viewModel = loadFiche("tx-2")

        viewModel.openCategorySearch()
        assertTrue(viewModel.uiState.value.choosingCategory)
        viewModel.chooseCategory("cat-food")
        advanceUntilIdle()

        val line = viewModel.uiState.value.current!!
        assertFalse(viewModel.uiState.value.choosingCategory)
        assertTrue(line.recognised)
        assertEquals("cat-food", line.categoryId)
        assertEquals("Alimentation", line.categoryPath)
    }

    @Test
    fun `the search can be closed without answering`() = runTest {
        val viewModel = loadFiche("tx-2")

        viewModel.openCategorySearch()
        viewModel.closeCategorySearch()

        assertFalse(viewModel.uiState.value.choosingCategory)
        coVerify(exactly = 0) { transactions.sortToCategory(any(), any(), any()) }
    }

    @Test
    fun `Toujours pour ce marchand goes with the answer, then is forgotten`() = runTest {
        coEvery { transactions.sortToCategory("tx-1", "cat-courses", true) } returns Result.success(
            SortResult(success = true, rule = SortedRule("rule-1", "CARREFOUR"), ruleCreated = true, alsoSorted = 0),
        )
        val viewModel = loadFiche("tx-1")

        viewModel.setAlwaysForMerchant(true)
        viewModel.validate()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.alwaysForMerchant)
        assertEquals("Règle « CARREFOUR » créée", state.message)
        coVerify(exactly = 1) { transactions.sortToCategory("tx-1", "cat-courses", true) }
        // A rule was created: the read follows, to bring what it filed besides.
        coVerify(exactly = 2) { reviews.getList(any(), any(), any(), any()) }

        viewModel.clearMessage()

        assertNull(viewModel.uiState.value.message)
    }

    @Test
    fun `a rule that filed other lines brings them back recognised and the counts follow the server`() = runTest {
        coEvery { transactions.sortToCategory("tx-1", "cat-courses", true) } returns Result.success(
            SortResult(success = true, rule = SortedRule("rule-1", "CARREFOUR"), ruleCreated = true, alsoSorted = 1),
        )
        val afterRule = queue(
            carrefour.copy(recognised = true, categoryId = "cat-courses", categoryPath = "Alimentation › Courses"),
            unknown.copy(recognised = true, categoryId = "cat-courses", categoryPath = "Alimentation › Courses"),
            netflix,
        )
        coEvery { reviews.getList(any(), any(), any(), any()) } returnsMany listOf(
            Result.success(queue(carrefour, unknown, netflix)),
            Result.success(afterRule),
        )
        val viewModel = loadFiche("tx-1")
        viewModel.setAlwaysForMerchant(true)

        viewModel.validate()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(0, state.counts.unrecognised)
        assertEquals(3, state.counts.recognised)
        assertTrue(state.items.all { it.recognised })
        assertEquals("tx-1", state.current?.id)
        assertTrue(state.ficheOpen)
        assertEquals("Règle « CARREFOUR » créée · 1 autre ligne classée", state.message)
    }

    @Test
    fun `Obligatoire and the type chosen on the fiche follow the answer`() = runTest {
        coEvery { transactions.sortToCategory("tx-1", "cat-courses", false) } returns Result.success(SortResult(success = true))
        coEvery { transactions.setQualification("tx-1", "cat-courses", mandatorySaving) } returns Result.success(Transaction(id = "tx-1", label = "x"))
        val viewModel = loadFiche("tx-1")

        viewModel.setQualification(mandatorySaving)
        viewModel.validate()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.current!!.recognised)
        assertEquals(Qualification.NONE, viewModel.uiState.value.qualification)
        assertNull(viewModel.uiState.value.sortError)
        coVerify(exactly = 1) { transactions.setQualification("tx-1", "cat-courses", mandatorySaving) }
    }

    @Test
    fun `a qualification lost after the answer leaves the line recognised and says what was lost`() = runTest {
        coEvery { transactions.sortToCategory("tx-1", "cat-courses", false) } returns Result.success(SortResult(success = true))
        coEvery { transactions.setQualification(any(), any(), any()) } returns Result.failure(RuntimeException("qualification refused"))
        val viewModel = loadFiche("tx-1")

        viewModel.setQualification(mandatorySaving)
        viewModel.validate()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.current!!.recognised)
        assertEquals("qualification refused", viewModel.uiState.value.sortError)
        assertFalse(viewModel.uiState.value.isSorting)
    }

    @Test
    fun `the qualification chosen for one line does not follow to the next`() = runTest {
        val viewModel = loadFiche("tx-1")
        viewModel.setQualification(mandatorySaving)
        viewModel.setAlwaysForMerchant(true)

        viewModel.next()

        assertEquals(Qualification.NONE, viewModel.uiState.value.qualification)
        assertFalse(viewModel.uiState.value.alwaysForMerchant)
    }

    // Virement interne

    @Test
    fun `an internal transfer lists the other legs, then files the line with the one chosen`() = runTest {
        val leg = TransferLeg(id = "leg-1", label = "VIR LIVRET", amountCents = 5320, accountName = "Livret")
        coEvery { transactions.getTransferCandidates("tx-1") } returns Result.success(listOf(leg))
        coEvery { transactions.sortAsInternalTransfer("tx-1", "leg-1") } returns Result.success(SortResult(success = true))
        val viewModel = loadFiche("tx-1")

        viewModel.openTransfer()
        advanceUntilIdle()

        assertEquals(listOf(leg), viewModel.uiState.value.transfer?.candidates)
        assertFalse(viewModel.uiState.value.transfer!!.isLoading)

        viewModel.markAsInternalTransfer("leg-1")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNull(state.transfer)
        assertTrue(state.current!!.recognised)
        assertEquals("Virement interne", state.current?.categoryPath)
        assertEquals(1, state.counts.unrecognised)
        coVerify(exactly = 1) { transactions.sortAsInternalTransfer("tx-1", "leg-1") }
    }

    @Test
    fun `an internal transfer can be answered without a counterpart even when no candidate could be read`() = runTest {
        coEvery { transactions.getTransferCandidates("tx-1") } returns Result.failure(RuntimeException("offline"))
        coEvery { transactions.sortAsInternalTransfer("tx-1", null) } returns Result.success(SortResult(success = true))
        val viewModel = loadFiche("tx-1")

        viewModel.openTransfer()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.transfer!!.candidates.isEmpty())
        assertFalse(viewModel.uiState.value.transfer!!.isLoading)

        viewModel.markAsInternalTransfer(null)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.current!!.recognised)
        coVerify(exactly = 1) { transactions.sortAsInternalTransfer("tx-1", null) }
    }

    @Test
    fun `the transfer choice can be closed`() = runTest {
        coEvery { transactions.getTransferCandidates("tx-1") } returns Result.success(emptyList())
        val viewModel = loadFiche("tx-1")
        viewModel.openTransfer()
        advanceUntilIdle()

        viewModel.closeTransfer()

        assertNull(viewModel.uiState.value.transfer)
    }

    // Correct, Corriger

    @Test
    fun `Correct checks a recognised line, which stays in the list ticked, the counts untouched`() = runTest {
        coEvery { transactions.controlRecognised(listOf("tx-4"), false) } returns Result.success(ControlResult(1, 0))
        val viewModel = loadFiche("tx-4")

        viewModel.control()
        assertTrue(viewModel.uiState.value.isControlling)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isControlling)
        assertTrue(state.isControlled(state.current!!))
        assertTrue(state.current!!.recognised)
        assertEquals(1, state.counts.recognised)
        assertEquals(listOf("tx-1", "tx-2", "tx-4"), state.visibleIds)
        coVerify(exactly = 1) { transactions.controlRecognised(listOf("tx-4"), false) }
    }

    @Test
    fun `a line already checked is not checked twice, nor one still to qualify`() = runTest {
        coEvery { transactions.controlRecognised(any(), any()) } returns Result.success(ControlResult(1, 0))
        val viewModel = loadFiche("tx-4")
        viewModel.control()
        advanceUntilIdle()

        viewModel.control()
        advanceUntilIdle()
        viewModel.select("tx-1")
        viewModel.control()
        advanceUntilIdle()

        coVerify(exactly = 1) { transactions.controlRecognised(any(), any()) }
    }

    @Test
    fun `a check that fails keeps the line to check, with the reason`() = runTest {
        coEvery { transactions.controlRecognised(any(), any()) } returns Result.failure(RuntimeException("unknown line"))
        val viewModel = loadFiche("tx-4")

        viewModel.control()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("unknown line", state.controlError)
        assertFalse(state.isControlling)
        assertFalse(state.isControlled(state.current!!))

        viewModel.clearControlError()

        assertNull(viewModel.uiState.value.controlError)
    }

    @Test
    fun `Corriger opens the correction on the line's category and reads what the operation set`() = runTest {
        coEvery { transactions.getTransaction("tx-4") } returns Result.success(Transaction(id = "tx-4", label = "NETFLIX.COM", qualification = mandatorySaving))
        val viewModel = loadFiche("tx-4")

        viewModel.startCorrection()
        val opening = viewModel.uiState.value.correction
        assertNotNull(opening)
        assertTrue(opening!!.isLoading)
        assertFalse(opening.canSave)
        advanceUntilIdle()

        val correction = viewModel.uiState.value.correction!!
        assertFalse(correction.isLoading)
        assertEquals("cat-loisirs", correction.categoryId)
        assertEquals(mandatorySaving, correction.qualification)
        assertTrue(correction.canSave)
    }

    @Test
    fun `a correction saved files the line by hand, checked, and it stays Reconnue`() = runTest {
        coEvery { transactions.getTransaction("tx-4") } returns Result.success(Transaction(id = "tx-4", label = "NETFLIX.COM"))
        coEvery { transactions.setQualification("tx-4", "cat-food", mandatorySaving) } returns Result.success(Transaction(id = "tx-4", label = "x"))
        val viewModel = loadFiche("tx-4")
        viewModel.startCorrection()
        advanceUntilIdle()

        viewModel.setCorrectionCategory("cat-food")
        viewModel.setCorrectionQualification(mandatorySaving)
        viewModel.saveCorrection()
        assertTrue(viewModel.uiState.value.correction!!.isSaving)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNull(state.correction)
        val line = state.current!!
        assertTrue(line.recognised)
        assertEquals("cat-food", line.categoryId)
        assertEquals("Alimentation", line.categoryPath)
        assertNull(line.qualifiedBy)
        assertNull(line.operationLabel)
        assertTrue(state.isControlled(line))
        assertEquals("Ligne corrigée", state.message)
        assertEquals(1, state.counts.recognised)
        coVerify(exactly = 1) { transactions.setQualification("tx-4", "cat-food", mandatorySaving) }
    }

    @Test
    fun `a correction refused stays open with the reason`() = runTest {
        coEvery { transactions.getTransaction("tx-4") } returns Result.success(Transaction(id = "tx-4", label = "NETFLIX.COM"))
        coEvery { transactions.setQualification(any(), any(), any()) } returns Result.failure(RuntimeException("refused"))
        val viewModel = loadFiche("tx-4")
        viewModel.startCorrection()
        advanceUntilIdle()

        viewModel.saveCorrection()
        advanceUntilIdle()

        val correction = viewModel.uiState.value.correction!!
        assertEquals("refused", correction.error)
        assertFalse(correction.isSaving)
        assertEquals("rule", viewModel.uiState.value.current?.qualifiedBy)
    }

    @Test
    fun `a qualification that cannot be read cancels the correction, so saving cannot erase it`() = runTest {
        coEvery { transactions.getTransaction("tx-4") } returns Result.failure(RuntimeException("offline"))
        val viewModel = loadFiche("tx-4")

        viewModel.startCorrection()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.correction)
        assertEquals("offline", viewModel.uiState.value.controlError)
    }

    @Test
    fun `a correction can be given up`() = runTest {
        coEvery { transactions.getTransaction("tx-4") } returns Result.success(Transaction(id = "tx-4", label = "NETFLIX.COM"))
        val viewModel = loadFiche("tx-4")
        viewModel.startCorrection()
        advanceUntilIdle()

        viewModel.cancelCorrection()

        assertNull(viewModel.uiState.value.correction)
        coVerify(exactly = 0) { transactions.setQualification(any(), any(), any()) }
    }

    @Test
    fun `Corriger is for recognised lines only`() = runTest {
        val viewModel = loadFiche("tx-1")

        viewModel.startCorrection()

        assertNull(viewModel.uiState.value.correction)
    }

    // Satisfaction

    @Test
    fun `a judgeable line can be kept, and the fiche shows it at once`() = runTest {
        coEvery { transactions.setRetrospect("tx-2", RETROSPECT_KEEP) } returns Result.success(Unit)
        val viewModel = loadFiche("tx-2")

        viewModel.judge(RETROSPECT_KEEP)
        assertTrue(viewModel.uiState.value.isJudging)
        advanceUntilIdle()

        assertEquals(RETROSPECT_KEEP, viewModel.uiState.value.current?.retrospect)
        assertFalse(viewModel.uiState.value.isJudging)
        coVerify(exactly = 1) { transactions.setRetrospect("tx-2", RETROSPECT_KEEP) }
    }

    @Test
    fun `a recognised expense can be judged too, then the tap on the same verdict takes it back`() = runTest {
        coEvery { transactions.setRetrospect("tx-4", RETROSPECT_AVOIDABLE) } returns Result.success(Unit)
        coEvery { transactions.setRetrospect("tx-4", RETROSPECT_UNRATED) } returns Result.success(Unit)
        val viewModel = loadFiche("tx-4")

        viewModel.judge(RETROSPECT_AVOIDABLE)
        advanceUntilIdle()
        assertEquals(RETROSPECT_AVOIDABLE, viewModel.uiState.value.current?.retrospect)

        viewModel.judge(RETROSPECT_AVOIDABLE)
        advanceUntilIdle()

        assertEquals(RETROSPECT_UNRATED, viewModel.uiState.value.current?.retrospect)
    }

    @Test
    fun `a verdict refused leaves the line with the one it had`() = runTest {
        coEvery { transactions.setRetrospect(any(), any()) } returns Result.failure(RuntimeException("offline"))
        val viewModel = loadFiche("tx-2")

        viewModel.judge(RETROSPECT_KEEP)
        advanceUntilIdle()

        assertEquals(RETROSPECT_UNRATED, viewModel.uiState.value.current?.retrospect)
        assertEquals("offline", viewModel.uiState.value.judgeError)

        viewModel.clearJudgeError()

        assertNull(viewModel.uiState.value.judgeError)
    }

    @Test
    fun `a line that is not an expense cannot be judged`() = runTest {
        val viewModel = loadFiche("tx-1")

        viewModel.judge(RETROSPECT_KEEP)
        advanceUntilIdle()

        coVerify(exactly = 0) { transactions.setRetrospect(any(), any()) }
    }

    @Test
    fun `a verdict survives a state pill and a return to the list`() = runTest {
        coEvery { transactions.setRetrospect("tx-2", RETROSPECT_KEEP) } returns Result.success(Unit)
        val viewModel = loadFiche("tx-2")
        viewModel.judge(RETROSPECT_KEEP)
        advanceUntilIdle()

        viewModel.backToList()
        viewModel.setState(ReviewState.UNRECOGNISED)

        assertEquals(RETROSPECT_KEEP, viewModel.uiState.value.items.first { it.id == "tx-2" }.retrospect)
    }

    // Contact

    private fun linked(contactId: String) =
        ContactCounterparty(id = "cp-1", contact = "/api/contacts/$contactId", label = "DUPONT MARTIN", transactionCount = 1)

    @Test
    fun `a line that asks opens the picker on the owner's contacts`() = runTest {
        val viewModel = loadFiche("tx-2")

        viewModel.openContactPicker()
        assertTrue(viewModel.uiState.value.contactChoice!!.isLoading)
        advanceUntilIdle()

        assertEquals(listOf(tom), viewModel.uiState.value.contactChoice?.contacts)
        assertFalse(viewModel.uiState.value.contactChoice!!.isLoading)

        viewModel.closeContactPicker()

        assertNull(viewModel.uiState.value.contactChoice)
    }

    @Test
    fun `picking a contact links the line's label to them, then the read brings whose it is`() = runTest {
        val attached = unknown.copy(contact = ContactRef("c-tom", "Tom"), asksForContact = false)
        coEvery { reviews.getList(any(), any(), any(), any()) } returnsMany listOf(
            Result.success(queue(carrefour, unknown, netflix)),
            Result.success(queue(carrefour, attached, netflix)),
        )
        coEvery { contacts.link("c-tom", "DUPONT MARTIN") } returns Result.success(linked("c-tom"))
        val viewModel = loadFiche("tx-2")
        viewModel.openContactPicker()
        advanceUntilIdle()

        viewModel.pickContact(tom)
        assertTrue(viewModel.uiState.value.isLinking)
        assertNull(viewModel.uiState.value.contactChoice)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLinking)
        assertEquals("Tom", state.current?.contact?.name)
        assertFalse(state.current!!.asksForContact)
        assertEquals("Associé à Tom", state.message)
        assertEquals("tx-2", state.current?.id)
        coVerify(exactly = 0) { contacts.createContact(any()) }
    }

    @Test
    fun `creating a contact from the picker creates them first, then links the label`() = runTest {
        coEvery { contacts.createContact("Tom") } returns Result.success(tom)
        coEvery { contacts.link("c-tom", "DUPONT MARTIN") } returns Result.success(linked("c-tom"))
        val viewModel = loadFiche("tx-2")

        viewModel.createContact("  Tom ")
        advanceUntilIdle()

        assertEquals("Associé à Tom", viewModel.uiState.value.message)
        coVerify(exactly = 1) { contacts.createContact("Tom") }
        coVerify(exactly = 1) { contacts.link("c-tom", "DUPONT MARTIN") }
    }

    @Test
    fun `a label somebody else already has is refused with the server's words, and nothing changes`() = runTest {
        coEvery { contacts.link("c-tom", "DUPONT MARTIN") } returns
            Result.failure(ApiRefusalException(400, "« DUPONT MARTIN » is already linked to Anna."))
        val viewModel = loadFiche("tx-2")

        viewModel.pickContact(tom)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLinking)
        assertEquals("« DUPONT MARTIN » is already linked to Anna.", state.contactError)
        assertTrue(state.current!!.asksForContact)

        viewModel.clearContactError()

        assertNull(viewModel.uiState.value.contactError)
    }

    @Test
    fun `a blank name creates nothing`() = runTest {
        val viewModel = loadFiche("tx-2")

        viewModel.createContact("   ")
        advanceUntilIdle()

        coVerify(exactly = 0) { contacts.createContact(any()) }
    }

    // Créer une opération

    @Test
    fun `Créer une opération opens the rule draft from the fiche, with the suggested heading and the chosen qualification`() = runTest {
        coEvery { rules.previewRule(any()) } returns Result.success(RulePreview())
        val viewModel = loadFiche("tx-1")

        viewModel.setQualification(mandatorySaving)
        viewModel.openRuleDraft()
        advanceUntilIdle()

        val draft = viewModel.uiState.value.ruleDraft
        assertNotNull(draft)
        assertEquals("CARREFOUR MARKET 1234", draft!!.labelPattern)
        assertEquals("debit", draft.direction)
        assertEquals("cat-courses", draft.categoryId)
        assertEquals(mandatorySaving, draft.qualification)

        viewModel.closeRuleDraft()

        assertNull(viewModel.uiState.value.ruleDraft)
    }

    @Test
    fun `saving the operation applies it to the history, then reads the list again`() = runTest {
        val created = slot<CategorizationRuleCreateRequest>()
        coEvery { rules.previewRule(any()) } returns Result.success(RulePreview())
        coEvery { rules.createRule(capture(created)) } returns Result.success(CategorizationRule(id = "r1", labelPattern = "CARREFOUR MARKET 1234"))
        coEvery { reviews.getList(any(), any(), any(), any()) } returnsMany listOf(
            Result.success(queue(carrefour, unknown, netflix)),
            Result.success(queue(carrefour.copy(recognised = true, categoryId = "cat-courses", categoryPath = "Alimentation › Courses"), unknown, netflix)),
        )
        val viewModel = loadFiche("tx-1")
        viewModel.openRuleDraft()
        advanceUntilIdle()

        viewModel.saveRule()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(created.captured.applyToExisting)
        assertEquals("/api/categories/cat-courses", created.captured.category)
        assertNull(state.ruleDraft)
        assertEquals("Opération créée", state.message)
        assertTrue(state.current!!.recognised)
        assertEquals(1, state.counts.unrecognised)
        assertEquals(2, state.counts.recognised)
    }

    @Test
    fun `an operation that cannot be saved keeps its draft with the reason`() = runTest {
        coEvery { rules.previewRule(any()) } returns Result.success(RulePreview())
        coEvery { rules.createRule(any()) } returns Result.failure(RuntimeException("refused"))
        val viewModel = loadFiche("tx-1")
        viewModel.openRuleDraft()
        advanceUntilIdle()

        viewModel.saveRule()
        advanceUntilIdle()

        assertEquals("refused", viewModel.uiState.value.ruleDraft?.error)
        assertFalse(viewModel.uiState.value.current!!.recognised)
        coVerify(exactly = 1) { reviews.getList(any(), any(), any(), any()) }
    }

    // Ce virement est

    private val tomRepayment = ReviewQueueItem(
        id = "tx-5",
        label = "VIR. DE TOM MARTIN",
        amountCents = 40000,
        bookedAt = "2026-10-09",
        contact = ContactRef("c-tom", "Tom"),
        reason = TO_SORT_REASON_PEER_TRANSFER,
        asksForPeerKind = true,
    )

    private val tomAllocation = TransactionAllocation(
        transactionId = "tx-5",
        amountCents = 40000,
        canBeAllocated = true,
        contact = ContactRef("c-tom", "Tom"),
        unallocatedCents = 40000,
        candidates = listOf(
            PeerAllocationCandidate(
                shareId = "share-gite", `object` = "Gîte de Crozon", contactId = "c-tom", contactName = "Tom",
                amountCents = 20000, remainingCents = 20000, availableCents = 20000, fromContact = true,
            ),
        ),
    )

    private fun TestScope.loadTomFirst(line: ReviewQueueItem = tomRepayment): ReviewListViewModel {
        coEvery { reviews.getList(any(), any(), any(), any()) } returns Result.success(queue(line, carrefour))
        coEvery { peerDebts.getAllocation("tx-5") } returns Result.success(tomAllocation)
        val viewModel = load()
        viewModel.select("tx-5")
        advanceUntilIdle()
        return viewModel
    }

    @Test
    fun `a transfer from a contact asks Ce virement est, with the créances it may cover`() = runTest {
        val viewModel = loadTomFirst()

        val peer = viewModel.uiState.value.currentPeer
        assertNotNull(peer)
        assertEquals("tx-5", peer!!.transactionId)
        assertEquals(listOf("Gîte de Crozon"), peer.choices.map { it.candidate.`object` })
        assertNull(peer.kind)
    }

    @Test
    fun `a line that does not ask reads no allocation`() = runTest {
        val viewModel = loadFiche("tx-1")
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.currentPeer)
        coVerify(exactly = 0) { peerDebts.getAllocation(any()) }
    }

    @Test
    fun `allocating the transfer to the gîte saves it and the line becomes Reconnue in place`() = runTest {
        val sent = slot<PeerAllocationRequest>()
        coEvery { peerDebts.setAllocation("tx-5", capture(sent)) } returns
            Result.success(tomAllocation.copy(peerKind = PeerKind.REPAYMENT, allocatedCents = 20000, unallocatedCents = 20000))
        val viewModel = loadTomFirst()
        viewModel.choosePeerKind(PeerKind.REPAYMENT)
        viewModel.togglePeerShare("share-gite")
        assertEquals(20000, viewModel.uiState.value.currentPeer!!.unallocatedCents)

        viewModel.savePeer()
        assertTrue(viewModel.uiState.value.currentPeer!!.isSaving)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(PeerAllocationRequest(PeerKind.REPAYMENT, allocations = listOf(PeerAllocationRequestLine("share-gite", 20000))), sent.captured)
        assertEquals("tx-5", state.current?.id)
        assertTrue(state.current!!.recognised)
        assertFalse(state.current!!.asksForPeerKind)
        assertNull(state.currentPeer)
        assertEquals("Remboursement enregistré", state.message)
        assertEquals(1, state.counts.unrecognised)
    }

    @Test
    fun `a line still without a category stays to qualify once answered, without the question`() = runTest {
        coEvery { peerDebts.setAllocation("tx-5", any()) } returns Result.success(tomAllocation.copy(peerKind = PeerKind.INCOME))
        val viewModel = loadTomFirst(tomRepayment.copy(reason = TO_SORT_REASON_CATEGORY))
        viewModel.choosePeerKind(PeerKind.INCOME)

        viewModel.savePeer()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("tx-5", state.current?.id)
        assertFalse(state.current!!.recognised)
        assertFalse(state.current!!.asksForPeerKind)
        assertNull(state.currentPeer)
    }

    @Test
    fun `a refused answer keeps the draft with the server's words`() = runTest {
        coEvery { peerDebts.setAllocation("tx-5", any()) } returns Result.failure(ApiRefusalException(400, "Trop sur cette créance"))
        val viewModel = loadTomFirst()
        viewModel.choosePeerKind(PeerKind.REPAYMENT)
        viewModel.togglePeerShare("share-gite")

        viewModel.savePeer()
        advanceUntilIdle()

        val peer = viewModel.uiState.value.currentPeer!!
        assertEquals("Trop sur cette créance", peer.error)
        assertFalse(peer.isSaving)
        assertTrue(peer.choices.single().selected)
        assertFalse(viewModel.uiState.value.current!!.recognised)
    }

    @Test
    fun `the person of a new dette is picked in the full-screen picker`() = runTest {
        val viewModel = loadTomFirst()
        viewModel.choosePeerKind(PeerKind.NEW_DEBT)

        viewModel.openPeerContactPicker()
        advanceUntilIdle()

        assertEquals(listOf(tom), viewModel.uiState.value.peerContacts?.contacts)

        viewModel.pickPeerContact(tom)

        assertNull(viewModel.uiState.value.peerContacts)
        assertEquals("c-tom", viewModel.uiState.value.currentPeer?.personId)
    }

    @Test
    fun `a contact created from the peer picker becomes the person`() = runTest {
        coEvery { contacts.createContact("Inès") } returns Result.success(Contact(id = "c-ines", name = "Inès"))
        val viewModel = loadTomFirst()
        viewModel.choosePeerKind(PeerKind.NEW_DEBT)
        viewModel.openPeerContactPicker()
        advanceUntilIdle()

        viewModel.createPeerContact("Inès")
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.peerContacts)
        assertEquals("c-ines", viewModel.uiState.value.currentPeer?.personId)
    }
}

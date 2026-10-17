package com.maggie.app.ui.screens.finance

import com.maggie.app.data.api.ApiRefusalException
import com.maggie.app.data.api.CategorizationRuleCreateRequest
import com.maggie.app.data.model.Category
import com.maggie.app.data.model.CategorizationRule
import com.maggie.app.data.model.Qualification
import com.maggie.app.data.model.QualificationKind
import com.maggie.app.data.model.RETROSPECT_AVOIDABLE
import com.maggie.app.data.model.RETROSPECT_KEEP
import com.maggie.app.data.model.RETROSPECT_UNRATED
import com.maggie.app.data.model.RulePreview
import com.maggie.app.data.model.Transaction
import com.maggie.app.data.model.Contact
import com.maggie.app.data.model.ContactCounterparty
import com.maggie.app.data.model.ContactRef
import com.maggie.app.data.model.SortResult
import com.maggie.app.data.model.SortSuggestion
import com.maggie.app.data.model.SortedRule
import com.maggie.app.data.model.ToSortPile
import com.maggie.app.data.model.TransactionToSort
import com.maggie.app.data.model.TransferLeg
import com.maggie.app.data.model.PeerAllocationCandidate
import com.maggie.app.data.model.PeerAllocationRequest
import com.maggie.app.data.model.PeerAllocationRequestLine
import com.maggie.app.data.model.PeerKind
import com.maggie.app.data.model.TO_SORT_REASON_CATEGORY
import com.maggie.app.data.model.TO_SORT_REASON_PEER_TRANSFER
import com.maggie.app.data.model.TransactionAllocation
import com.maggie.app.data.repository.CategorizationRuleRepository
import com.maggie.app.data.repository.CategoryRepository
import com.maggie.app.data.repository.ContactRepository
import com.maggie.app.data.repository.PeerDebtRepository
import com.maggie.app.data.repository.TransactionRepository
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

@OptIn(ExperimentalCoroutinesApi::class)
class ToSortViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var transactions: TransactionRepository
    private lateinit var categories: CategoryRepository
    private lateinit var rules: CategorizationRuleRepository
    private lateinit var contacts: ContactRepository
    private lateinit var peerDebts: PeerDebtRepository

    private val carrefour = TransactionToSort(
        id = "tx-1",
        label = "CARREFOUR MARKET 1234",
        amountCents = -5320,
        bookedAt = "2026-10-12",
        accountName = "Courant",
        suggestion = SortSuggestion("cat-courses", "Alimentation › Courses", "rule", "Règle « contient CARREFOUR »"),
    )

    private val unknown = TransactionToSort(
        id = "tx-2",
        label = "DUPONT MARTIN",
        amountCents = -1200,
        bookedAt = "2026-10-11",
        judgeable = true,
    )

    private val mandatorySaving = Qualification(mandatory = true, kind = QualificationKind.Saving)

    private val tomTransfer = TransactionToSort(
        id = "tx-3",
        label = "VIR. DE TOM MARTIN",
        amountCents = 40000,
        bookedAt = "2026-10-13",
        asksForContact = true,
        counterpartyLabel = "VIR. DE TOM MARTIN",
    )

    private val tom = Contact(id = "c-tom", name = "Tom")

    private val categoryList = listOf(
        Category(id = "cat-food", name = "Alimentation"),
        Category(id = "cat-courses", name = "Courses", parent = "/api/categories/cat-food"),
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        transactions = mockk()
        categories = mockk()
        rules = mockk()
        contacts = mockk()
        peerDebts = mockk()
        coEvery { contacts.getContacts() } returns Result.success(listOf(tom))
        coEvery { categories.getCategories() } returns Result.success(categoryList)
        coEvery { transactions.getToSort(oldestFirst = true) } returns Result.success(ToSortPile(2, listOf(carrefour, unknown)))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun load() = ToSortViewModel(transactions, categories, rules, contacts, peerDebts)

    @Test
    fun `the pile is loading until the server has answered`() = runTest {
        val viewModel = load()

        assertTrue(viewModel.uiState.value.isLoading)
        assertFalse(viewModel.uiState.value.loaded)
        assertNull(viewModel.uiState.value.current)

        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test
    fun `the oldest line is the card, with its suggestion and the whole count`() = runTest {
        val viewModel = load()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.loaded)
        assertEquals("tx-1", state.current?.id)
        assertEquals("Règle « contient CARREFOUR »", state.current?.suggestion?.reason)
        assertEquals(2, state.total)
        assertTrue(state.canValidate)
        assertFalse(state.alwaysForMerchant)
    }

    @Test
    fun `a line without suggestion cannot be validated`() = runTest {
        val viewModel = load()
        advanceUntilIdle()

        viewModel.select("tx-2")

        assertEquals("tx-2", viewModel.uiState.value.current?.id)
        assertFalse(viewModel.uiState.value.canValidate)
    }

    @Test
    fun `validating files the line under its suggestion and moves on to the next`() = runTest {
        coEvery { transactions.sortToCategory("tx-1", "cat-courses", false) } returns
            Result.success(SortResult(success = true, remaining = 1))
        coEvery { transactions.getToSort(oldestFirst = true) } returnsMany listOf(
            Result.success(ToSortPile(2, listOf(carrefour, unknown))),
            Result.success(ToSortPile(1, listOf(unknown))),
        )
        val viewModel = load()
        advanceUntilIdle()

        viewModel.validate()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("tx-2", state.current?.id)
        assertEquals(1, state.total)
        assertFalse(state.isSorting)
        assertNull(state.sortError)
        coVerify(exactly = 1) { transactions.sortToCategory("tx-1", "cat-courses", false) }
    }

    @Test
    fun `the last answer leaves an empty pile`() = runTest {
        coEvery { transactions.getToSort(oldestFirst = true) } returnsMany listOf(
            Result.success(ToSortPile(1, listOf(carrefour))),
            Result.success(ToSortPile(0, emptyList())),
        )
        coEvery { transactions.sortToCategory(any(), any(), any()) } returns Result.success(SortResult(success = true, remaining = 0))
        val viewModel = load()
        advanceUntilIdle()

        viewModel.validate()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isEmpty)
        assertNull(viewModel.uiState.value.current)
    }

    @Test
    fun `an empty pile is said once it was read, not while loading`() = runTest {
        coEvery { transactions.getToSort(oldestFirst = true) } returns Result.success(ToSortPile(0, emptyList()))
        val viewModel = load()

        assertFalse(viewModel.uiState.value.isEmpty)

        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isEmpty)
    }

    @Test
    fun `a pile that cannot be read is an error to retry, not an empty pile`() = runTest {
        coEvery { transactions.getToSort(oldestFirst = true) } returns Result.failure(RuntimeException("boom"))
        val viewModel = load()
        advanceUntilIdle()

        assertEquals("boom", viewModel.uiState.value.loadError)
        assertFalse(viewModel.uiState.value.isEmpty)
        assertFalse(viewModel.uiState.value.loaded)

        coEvery { transactions.getToSort(oldestFirst = true) } returns Result.success(ToSortPile(1, listOf(carrefour)))
        viewModel.refresh()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.loadError)
        assertEquals("tx-1", viewModel.uiState.value.current?.id)
    }

    @Test
    fun `a refused answer keeps the card where it was and says so`() = runTest {
        coEvery { transactions.sortToCategory(any(), any(), any()) } returns Result.failure(RuntimeException("already sorted"))
        val viewModel = load()
        advanceUntilIdle()

        viewModel.validate()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("tx-1", state.current?.id)
        assertEquals("already sorted", state.sortError)
        assertFalse(state.isSorting)

        viewModel.clearSortError()

        assertNull(viewModel.uiState.value.sortError)
    }

    @Test
    fun `another category is sent as chosen, with the suggestion left alone`() = runTest {
        coEvery { transactions.sortToCategory("tx-2", "cat-food", false) } returns Result.success(SortResult(success = true, remaining = 1))
        val viewModel = load()
        advanceUntilIdle()
        viewModel.select("tx-2")

        viewModel.openCategorySearch()
        assertTrue(viewModel.uiState.value.choosingCategory)
        viewModel.chooseCategory("cat-food")
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.choosingCategory)
        coVerify(exactly = 1) { transactions.sortToCategory("tx-2", "cat-food", false) }
    }

    @Test
    fun `closing the search without a pick answers nothing`() = runTest {
        val viewModel = load()
        advanceUntilIdle()

        viewModel.openCategorySearch()
        viewModel.closeCategorySearch()

        assertFalse(viewModel.uiState.value.choosingCategory)
        coVerify(exactly = 0) { transactions.sortToCategory(any(), any(), any()) }
    }

    @Test
    fun `always for this merchant is sent with the answer, tells what the rule did and falls back to off`() = runTest {
        coEvery { transactions.sortToCategory("tx-1", "cat-courses", true) } returns Result.success(
            SortResult(
                success = true,
                rule = SortedRule("rule-1", "CARREFOUR"),
                ruleCreated = true,
                alsoSorted = 2,
                remaining = 0,
            ),
        )
        val viewModel = load()
        advanceUntilIdle()

        viewModel.setAlwaysForMerchant(true)
        assertTrue(viewModel.uiState.value.alwaysForMerchant)
        viewModel.validate()
        advanceUntilIdle()

        assertEquals("Règle « CARREFOUR » créée · 2 autres lignes classées", viewModel.uiState.value.message)
        assertFalse(viewModel.uiState.value.alwaysForMerchant)
        coVerify(exactly = 1) { transactions.sortToCategory("tx-1", "cat-courses", true) }
    }

    @Test
    fun `a line a rule filed meanwhile is gone from the pile once it is read again`() = runTest {
        coEvery { transactions.sortToCategory(any(), any(), any()) } returns Result.success(SortResult(success = true, remaining = 0))
        coEvery { transactions.getToSort(oldestFirst = true) } returnsMany listOf(
            Result.success(ToSortPile(2, listOf(carrefour, unknown))),
            Result.success(ToSortPile(0, emptyList())),
        )
        val viewModel = load()
        advanceUntilIdle()

        viewModel.validate()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.pile.isEmpty())
        coVerify(exactly = 2) { transactions.getToSort(oldestFirst = true) }
    }

    @Test
    fun `an internal transfer lists the other legs, then marks the line with the one chosen`() = runTest {
        val leg = TransferLeg(id = "leg-1", label = "VIR LIVRET", amountCents = 5320, accountName = "Livret")
        coEvery { transactions.getTransferCandidates("tx-1") } returns Result.success(listOf(leg))
        coEvery { transactions.sortAsInternalTransfer("tx-1", "leg-1") } returns Result.success(SortResult(success = true, remaining = 1))
        val viewModel = load()
        advanceUntilIdle()

        viewModel.openTransfer()
        advanceUntilIdle()

        assertEquals(listOf(leg), viewModel.uiState.value.transfer?.candidates)
        assertFalse(viewModel.uiState.value.transfer!!.isLoading)

        viewModel.markAsInternalTransfer("leg-1")
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.transfer)
        coVerify(exactly = 1) { transactions.sortAsInternalTransfer("tx-1", "leg-1") }
    }

    @Test
    fun `an internal transfer can be answered without a counterpart, even when no candidate could be read`() = runTest {
        coEvery { transactions.getTransferCandidates("tx-1") } returns Result.failure(RuntimeException("boom"))
        coEvery { transactions.sortAsInternalTransfer("tx-1", null) } returns Result.success(SortResult(success = true, remaining = 1))
        val viewModel = load()
        advanceUntilIdle()

        viewModel.openTransfer()
        advanceUntilIdle()

        assertNotNull(viewModel.uiState.value.transfer)
        assertTrue(viewModel.uiState.value.transfer!!.candidates.isEmpty())

        viewModel.markAsInternalTransfer(null)
        advanceUntilIdle()

        coVerify(exactly = 1) { transactions.sortAsInternalTransfer("tx-1", null) }
    }

    @Test
    fun `a second tap while an answer is on its way sends nothing more`() = runTest {
        coEvery { transactions.sortToCategory(any(), any(), any()) } returns Result.success(SortResult(success = true, remaining = 1))
        val viewModel = load()
        advanceUntilIdle()

        viewModel.validate()
        viewModel.validate()
        advanceUntilIdle()

        coVerify(exactly = 1) { transactions.sortToCategory(any(), any(), any()) }
    }

    @Test
    fun `the pile is read oldest first`() = runTest {
        load()
        advanceUntilIdle()

        coVerify(exactly = 1) { transactions.getToSort(oldestFirst = true) }
        coVerify(exactly = 0) { transactions.getToSort(limit = any(), oldestFirst = false) }
    }

    @Test
    fun `a qualification chosen on the card follows the category the line is filed under`() = runTest {
        coEvery { transactions.sortToCategory("tx-1", "cat-courses", false) } returns Result.success(SortResult(success = true, remaining = 1))
        coEvery { transactions.setQualification("tx-1", "cat-courses", mandatorySaving) } returns Result.success(mockk<Transaction>())
        val viewModel = load()
        advanceUntilIdle()

        viewModel.setQualification(mandatorySaving)
        assertEquals(mandatorySaving, viewModel.uiState.value.qualification)
        viewModel.validate()
        advanceUntilIdle()

        coVerify(exactly = 1) { transactions.setQualification("tx-1", "cat-courses", mandatorySaving) }
        assertEquals(Qualification.NONE, viewModel.uiState.value.qualification)
        assertNull(viewModel.uiState.value.sortError)
    }

    @Test
    fun `no qualification chosen sends no second request`() = runTest {
        coEvery { transactions.sortToCategory(any(), any(), any()) } returns Result.success(SortResult(success = true, remaining = 1))
        val viewModel = load()
        advanceUntilIdle()

        viewModel.validate()
        advanceUntilIdle()

        coVerify(exactly = 0) { transactions.setQualification(any(), any(), any()) }
    }

    @Test
    fun `a qualification that is refused is told, and the line which is filed anyway is read again`() = runTest {
        coEvery { transactions.sortToCategory(any(), any(), any()) } returns Result.success(SortResult(success = true, remaining = 1))
        coEvery { transactions.setQualification(any(), any(), any()) } returns Result.failure(RuntimeException("refused"))
        val viewModel = load()
        advanceUntilIdle()

        viewModel.setQualification(mandatorySaving)
        viewModel.validate()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("refused", state.sortError)
        assertFalse(state.isSorting)
        assertEquals(Qualification.NONE, state.qualification)
        coVerify(exactly = 2) { transactions.getToSort(oldestFirst = true) }
    }

    @Test
    fun `picking another card forgets the qualification of the previous one`() = runTest {
        val viewModel = load()
        advanceUntilIdle()

        viewModel.setQualification(mandatorySaving)
        viewModel.select("tx-2")

        assertEquals(Qualification.NONE, viewModel.uiState.value.qualification)
    }

    @Test
    fun `a judgeable line can be kept, and the card shows it at once`() = runTest {
        coEvery { transactions.setRetrospect("tx-2", RETROSPECT_KEEP) } returns Result.success(Unit)
        val viewModel = load()
        advanceUntilIdle()
        viewModel.select("tx-2")

        viewModel.judge(RETROSPECT_KEEP)
        advanceUntilIdle()

        assertEquals(RETROSPECT_KEEP, viewModel.uiState.value.current?.retrospect)
        assertFalse(viewModel.uiState.value.isJudging)
        assertEquals(2, viewModel.uiState.value.pile.size)
        coVerify(exactly = 1) { transactions.setRetrospect("tx-2", RETROSPECT_KEEP) }
    }

    @Test
    fun `a judgeable line can be called avoidable, then the tap on the same verdict takes it back`() = runTest {
        coEvery { transactions.setRetrospect(any(), any()) } returns Result.success(Unit)
        val viewModel = load()
        advanceUntilIdle()
        viewModel.select("tx-2")

        viewModel.judge(RETROSPECT_AVOIDABLE)
        advanceUntilIdle()
        assertEquals(RETROSPECT_AVOIDABLE, viewModel.uiState.value.current?.retrospect)

        viewModel.judge(RETROSPECT_AVOIDABLE)
        advanceUntilIdle()

        assertEquals(RETROSPECT_UNRATED, viewModel.uiState.value.current?.retrospect)
        coVerify(exactly = 1) { transactions.setRetrospect("tx-2", RETROSPECT_AVOIDABLE) }
        coVerify(exactly = 1) { transactions.setRetrospect("tx-2", RETROSPECT_UNRATED) }
    }

    @Test
    fun `the other verdict replaces the first one`() = runTest {
        coEvery { transactions.setRetrospect(any(), any()) } returns Result.success(Unit)
        val viewModel = load()
        advanceUntilIdle()
        viewModel.select("tx-2")

        viewModel.judge(RETROSPECT_KEEP)
        advanceUntilIdle()
        viewModel.judge(RETROSPECT_AVOIDABLE)
        advanceUntilIdle()

        assertEquals(RETROSPECT_AVOIDABLE, viewModel.uiState.value.current?.retrospect)
    }

    @Test
    fun `a line that cannot be judged sends nothing`() = runTest {
        val viewModel = load()
        advanceUntilIdle()

        viewModel.judge(RETROSPECT_KEEP)
        advanceUntilIdle()

        assertEquals(RETROSPECT_UNRATED, viewModel.uiState.value.current?.retrospect)
        coVerify(exactly = 0) { transactions.setRetrospect(any(), any()) }
    }

    @Test
    fun `a refused verdict leaves the line as it was and says so`() = runTest {
        coEvery { transactions.setRetrospect(any(), any()) } returns Result.failure(RuntimeException("refused"))
        val viewModel = load()
        advanceUntilIdle()
        viewModel.select("tx-2")

        viewModel.judge(RETROSPECT_KEEP)
        advanceUntilIdle()

        assertEquals(RETROSPECT_UNRATED, viewModel.uiState.value.current?.retrospect)
        assertEquals("refused", viewModel.uiState.value.judgeError)
        assertFalse(viewModel.uiState.value.isJudging)

        viewModel.clearJudgeError()

        assertNull(viewModel.uiState.value.judgeError)
    }

    @Test
    fun `a second tap while a verdict is on its way sends nothing more`() = runTest {
        coEvery { transactions.setRetrospect(any(), any()) } returns Result.success(Unit)
        val viewModel = load()
        advanceUntilIdle()
        viewModel.select("tx-2")

        viewModel.judge(RETROSPECT_KEEP)
        viewModel.judge(RETROSPECT_AVOIDABLE)
        advanceUntilIdle()

        coVerify(exactly = 1) { transactions.setRetrospect(any(), any()) }
    }

    @Test
    fun `create a filter opens the rule draft from the card, with the suggested heading and the chosen qualification`() = runTest {
        coEvery { rules.previewRule(any()) } returns Result.success(RulePreview())
        val viewModel = load()
        advanceUntilIdle()

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
    fun `saving the filter applies it to the history, then reads the pile again`() = runTest {
        val created = slot<CategorizationRuleCreateRequest>()
        coEvery { rules.previewRule(any()) } returns Result.success(RulePreview())
        coEvery { rules.createRule(capture(created)) } returns Result.success(CategorizationRule(id = "r1", labelPattern = "CARREFOUR MARKET 1234"))
        coEvery { transactions.getToSort(oldestFirst = true) } returnsMany listOf(
            Result.success(ToSortPile(2, listOf(carrefour, unknown))),
            Result.success(ToSortPile(1, listOf(unknown))),
        )
        val viewModel = load()
        advanceUntilIdle()
        viewModel.openRuleDraft()
        advanceUntilIdle()

        viewModel.saveRule()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(created.captured.applyToExisting)
        assertEquals("/api/categories/cat-courses", created.captured.category)
        assertNull(state.ruleDraft)
        assertEquals("Filtre créé", state.message)
        assertEquals("tx-2", state.current?.id)
        assertEquals(1, state.total)
    }

    @Test
    fun `a filter that cannot be saved keeps its draft with the reason`() = runTest {
        coEvery { rules.previewRule(any()) } returns Result.success(RulePreview())
        coEvery { rules.createRule(any()) } returns Result.failure(RuntimeException("refused"))
        val viewModel = load()
        advanceUntilIdle()
        viewModel.openRuleDraft()
        advanceUntilIdle()

        viewModel.saveRule()
        advanceUntilIdle()

        assertEquals("refused", viewModel.uiState.value.ruleDraft?.error)
        assertEquals("tx-1", viewModel.uiState.value.current?.id)
        coVerify(exactly = 1) { transactions.getToSort(oldestFirst = true) }
    }

    private fun linked(contactId: String) =
        ContactCounterparty(id = "cp-1", contact = "/api/contacts/$contactId", label = "VIR. DE TOM MARTIN", transactionCount = 1)

    @Test
    fun `a transfer nobody claims opens the picker on the owner's contacts`() = runTest {
        coEvery { transactions.getToSort(oldestFirst = true) } returns Result.success(ToSortPile(1, listOf(tomTransfer)))
        val viewModel = load()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.current!!.asksForContact)
        viewModel.openContactPicker()
        assertTrue(viewModel.uiState.value.contactChoice!!.isLoading)
        advanceUntilIdle()

        assertEquals(listOf(tom), viewModel.uiState.value.contactChoice!!.contacts)
        assertFalse(viewModel.uiState.value.contactChoice!!.isLoading)
    }

    @Test
    fun `the picker opens even when no contact could be read, to create one`() = runTest {
        coEvery { contacts.getContacts() } returns Result.failure(RuntimeException("Hors ligne"))
        coEvery { transactions.getToSort(oldestFirst = true) } returns Result.success(ToSortPile(1, listOf(tomTransfer)))
        val viewModel = load()
        advanceUntilIdle()

        viewModel.openContactPicker()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.contactChoice!!.contacts.isEmpty())
    }

    @Test
    fun `closing the picker links nothing`() = runTest {
        coEvery { transactions.getToSort(oldestFirst = true) } returns Result.success(ToSortPile(1, listOf(tomTransfer)))
        val viewModel = load()
        advanceUntilIdle()

        viewModel.openContactPicker()
        viewModel.closeContactPicker()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.contactChoice)
        coVerify(exactly = 0) { contacts.link(any(), any()) }
    }

    @Test
    fun `picking a contact links the line's label to them, then the line shows whose it is`() = runTest {
        val attached = tomTransfer.copy(contact = ContactRef("c-tom", "Tom"), asksForContact = false)
        coEvery { transactions.getToSort(oldestFirst = true) } returnsMany listOf(
            Result.success(ToSortPile(1, listOf(tomTransfer))),
            Result.success(ToSortPile(1, listOf(attached))),
        )
        coEvery { contacts.link("c-tom", "VIR. DE TOM MARTIN") } returns Result.success(linked("c-tom"))
        val viewModel = load()
        advanceUntilIdle()

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
        coVerify(exactly = 0) { contacts.createContact(any()) }
    }

    @Test
    fun `creating a contact from the picker creates them first, then links the label`() = runTest {
        coEvery { transactions.getToSort(oldestFirst = true) } returns Result.success(ToSortPile(1, listOf(tomTransfer)))
        coEvery { contacts.createContact("Tom") } returns Result.success(tom)
        coEvery { contacts.link("c-tom", "VIR. DE TOM MARTIN") } returns Result.success(linked("c-tom"))
        val viewModel = load()
        advanceUntilIdle()

        viewModel.openContactPicker()
        viewModel.createContact(" Tom ")
        advanceUntilIdle()

        coVerify(exactly = 1) { contacts.createContact("Tom") }
        coVerify(exactly = 1) { contacts.link("c-tom", "VIR. DE TOM MARTIN") }
        assertEquals("Associé à Tom", viewModel.uiState.value.message)
    }

    @Test
    fun `a contact that could not be created links nothing`() = runTest {
        coEvery { transactions.getToSort(oldestFirst = true) } returns Result.success(ToSortPile(1, listOf(tomTransfer)))
        coEvery { contacts.createContact(any()) } returns Result.failure(RuntimeException("Hors ligne"))
        val viewModel = load()
        advanceUntilIdle()

        viewModel.createContact("Tom")
        advanceUntilIdle()

        assertEquals("Hors ligne", viewModel.uiState.value.contactError)
        coVerify(exactly = 0) { contacts.link(any(), any()) }
    }

    @Test
    fun `a label already someone else's is refused in the server's words and the line stays as it was`() = runTest {
        coEvery { transactions.getToSort(oldestFirst = true) } returns Result.success(ToSortPile(1, listOf(tomTransfer)))
        coEvery { contacts.link("c-tom", any()) } returns
            Result.failure(ApiRefusalException(400, "« VIR. DE TOM MARTIN » is already linked to Anna."))
        val viewModel = load()
        advanceUntilIdle()

        viewModel.pickContact(tom)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("« VIR. DE TOM MARTIN » is already linked to Anna.", state.contactError)
        assertFalse(state.isLinking)
        assertTrue(state.current!!.asksForContact)

        viewModel.clearContactError()
        assertNull(viewModel.uiState.value.contactError)
    }

    @Test
    fun `a line without a counterparty label links its own label`() = runTest {
        val bare = tomTransfer.copy(counterpartyLabel = "")
        coEvery { transactions.getToSort(oldestFirst = true) } returns Result.success(ToSortPile(1, listOf(bare)))
        coEvery { contacts.link("c-tom", "VIR. DE TOM MARTIN") } returns Result.success(linked("c-tom"))
        val viewModel = load()
        advanceUntilIdle()

        viewModel.pickContact(tom)
        advanceUntilIdle()

        coVerify(exactly = 1) { contacts.link("c-tom", "VIR. DE TOM MARTIN") }
    }

    // « Ce virement est » on the card (MAG-453): Tom's 400 €, filed already, Tom owing 200 € on the gîte.

    private val tomRepayment = TransactionToSort(
        id = "tx-4",
        label = "VIR. DE TOM MARTIN",
        amountCents = 40000,
        bookedAt = "2026-10-09",
        contact = ContactRef("c-tom", "Tom"),
        reason = TO_SORT_REASON_PEER_TRANSFER,
        asksForPeerKind = true,
    )

    private val tomAllocation = TransactionAllocation(
        transactionId = "tx-4",
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

    private fun kotlinx.coroutines.test.TestScope.loadTomFirst(line: TransactionToSort = tomRepayment): ToSortViewModel {
        coEvery { transactions.getToSort(oldestFirst = true) } returns Result.success(ToSortPile(2, listOf(line, carrefour)))
        coEvery { peerDebts.getAllocation("tx-4") } returns Result.success(tomAllocation)
        val viewModel = load()
        advanceUntilIdle()
        return viewModel
    }

    @Test
    fun `a transfer from a contact asks Ce virement est, with the créances it may cover`() = runTest {
        val viewModel = loadTomFirst()

        val peer = viewModel.uiState.value.currentPeer
        assertNotNull(peer)
        assertEquals("tx-4", peer!!.transactionId)
        assertEquals(listOf("Gîte de Crozon"), peer.choices.map { it.candidate.`object` })
        assertNull(peer.kind)
    }

    @Test
    fun `a line that does not ask reads no allocation`() = runTest {
        val viewModel = load()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.currentPeer)
        coVerify(exactly = 0) { peerDebts.getAllocation(any()) }
    }

    @Test
    fun `allocating the transfer to the gîte saves it and brings the next card`() = runTest {
        val sent = slot<PeerAllocationRequest>()
        coEvery { peerDebts.setAllocation("tx-4", capture(sent)) } returns
            Result.success(tomAllocation.copy(peerKind = PeerKind.REPAYMENT, allocatedCents = 20000, unallocatedCents = 20000))
        val viewModel = loadTomFirst()
        viewModel.choosePeerKind(PeerKind.REPAYMENT)
        viewModel.togglePeerShare("share-gite")
        assertEquals(20000, viewModel.uiState.value.currentPeer!!.unallocatedCents)
        // The server's next read no longer has the line.
        coEvery { transactions.getToSort(oldestFirst = true) } returns Result.success(ToSortPile(1, listOf(carrefour)))

        viewModel.savePeer()
        assertTrue(viewModel.uiState.value.currentPeer!!.isSaving)
        advanceUntilIdle()

        assertEquals(PeerAllocationRequest(PeerKind.REPAYMENT, allocations = listOf(PeerAllocationRequestLine("share-gite", 20000))), sent.captured)
        assertEquals("tx-1", viewModel.uiState.value.current?.id)
        assertNull(viewModel.uiState.value.currentPeer)
        assertEquals("Remboursement enregistré", viewModel.uiState.value.message)
    }

    @Test
    fun `a line still without a category stays once answered, without the question`() = runTest {
        coEvery { peerDebts.setAllocation("tx-4", any()) } returns Result.success(tomAllocation.copy(peerKind = PeerKind.INCOME))
        val viewModel = loadTomFirst(tomRepayment.copy(reason = TO_SORT_REASON_CATEGORY))
        viewModel.choosePeerKind(PeerKind.INCOME)
        // The server keeps it in the pile, the question answered.
        coEvery { transactions.getToSort(oldestFirst = true) } returns
            Result.success(ToSortPile(2, listOf(tomRepayment.copy(reason = TO_SORT_REASON_CATEGORY, asksForPeerKind = false), carrefour)))

        viewModel.savePeer()
        advanceUntilIdle()

        assertEquals("tx-4", viewModel.uiState.value.current?.id)
        assertFalse(viewModel.uiState.value.current!!.asksForPeerKind)
        assertNull(viewModel.uiState.value.currentPeer)
    }

    @Test
    fun `a refused answer keeps the card and its draft, with the server's words`() = runTest {
        coEvery { peerDebts.setAllocation("tx-4", any()) } returns Result.failure(ApiRefusalException(400, "Trop sur cette créance"))
        val viewModel = loadTomFirst()
        viewModel.choosePeerKind(PeerKind.REPAYMENT)
        viewModel.togglePeerShare("share-gite")

        viewModel.savePeer()
        advanceUntilIdle()

        val peer = viewModel.uiState.value.currentPeer!!
        assertEquals("tx-4", viewModel.uiState.value.current?.id)
        assertEquals("Trop sur cette créance", peer.error)
        assertFalse(peer.isSaving)
        assertTrue(peer.choices.single().selected)
    }

    @Test
    fun `the person of a new dette is picked in the full-screen picker`() = runTest {
        val viewModel = loadTomFirst()
        viewModel.choosePeerKind(PeerKind.NEW_DEBT)
        assertEquals("Tom", viewModel.uiState.value.currentPeer!!.personName)

        viewModel.openPeerContactPicker()
        advanceUntilIdle()
        assertEquals(listOf("Tom"), viewModel.uiState.value.peerContacts!!.contacts.map { it.name })
        viewModel.pickPeerContact(Contact("c-julie", "Julie"))

        assertNull(viewModel.uiState.value.peerContacts)
        assertEquals("c-julie", viewModel.uiState.value.currentPeer!!.personId)
    }
}

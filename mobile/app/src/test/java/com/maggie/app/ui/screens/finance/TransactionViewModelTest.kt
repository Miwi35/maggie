package com.maggie.app.ui.screens.finance

import com.maggie.app.data.api.CategorizationRuleCreateRequest
import com.maggie.app.data.api.TransactionCreateRequest
import com.maggie.app.data.model.Category
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureEvent
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.mercure.MercureTopics
import com.maggie.app.data.model.AccountIncident
import com.maggie.app.data.model.CategorizationRule
import com.maggie.app.data.model.Contact
import com.maggie.app.data.model.MyShare
import com.maggie.app.data.model.MyShareRequest
import com.maggie.app.data.model.MyShareRequestShare
import com.maggie.app.data.model.PeerDebt
import com.maggie.app.data.model.formatCents
import com.maggie.app.data.model.PeerDebtShare
import com.maggie.app.data.model.ContactRef
import com.maggie.app.data.model.PeerAllocationCandidate
import com.maggie.app.data.model.PeerAllocationDebt
import com.maggie.app.data.model.PeerAllocationLine
import com.maggie.app.data.model.PeerAllocationRequest
import com.maggie.app.data.model.PeerAllocationRequestLine
import com.maggie.app.data.model.PeerKind
import com.maggie.app.data.model.TransactionAllocation
import com.maggie.app.data.model.TransactionDebt
import com.maggie.app.data.model.TransactionDebtRequest
import com.maggie.app.data.model.Envelope
import com.maggie.app.data.model.Qualification
import com.maggie.app.data.model.QualificationKind
import com.maggie.app.data.model.RecurringOperationRecord
import com.maggie.app.data.model.RulePreview
import com.maggie.app.data.model.RulePreviewMatch
import com.maggie.app.data.model.RulePreviewRequest
import com.maggie.app.data.model.Transaction
import com.maggie.app.data.model.TransactionSheetSection
import com.maggie.app.data.model.visibleSheetSections
import com.maggie.app.data.model.TransferInfo
import com.maggie.app.data.model.TransferLeg
import com.maggie.app.data.repository.CategorizationRuleRepository
import com.maggie.app.data.repository.CategoryRepository
import com.maggie.app.data.repository.ContactRepository
import com.maggie.app.data.repository.PeerDebtRepository
import com.maggie.app.data.repository.RecurringOperationRepository
import com.maggie.app.data.repository.TransactionPage
import com.maggie.app.data.repository.TransactionRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.verify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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

/** A page of lines the account holds [total] of, all of them unless said otherwise. */
private fun pageOf(lines: List<Transaction>, total: Int? = lines.size) = Result.success(TransactionPage(lines, total))

@OptIn(ExperimentalCoroutinesApi::class)
class TransactionViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var transactionRepository: TransactionRepository
    private lateinit var categoryRepository: CategoryRepository
    private lateinit var mercureService: MercureService
    private lateinit var authRepository: AuthRepository
    private lateinit var recurringRepository: RecurringOperationRepository
    private lateinit var ruleRepository: CategorizationRuleRepository
    private lateinit var peerDebtRepository: PeerDebtRepository
    private lateinit var contactRepository: ContactRepository
    private lateinit var viewModel: TransactionViewModel

    private val accountId = "acc-1"
    private val sampleTransactions = listOf(
        Transaction(id = "tx-1", label = "Supermarché", amountCents = -4599),
        Transaction(id = "tx-2", label = "Salaire", amountCents = 250000),
    )

    private val sampleCategories = listOf(
        Category(id = "cat-1", name = "Courses"),
        Category(id = "cat-2", name = "Loisirs"),
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        transactionRepository = mockk()
        categoryRepository = mockk()
        mercureService = mockk()
        authRepository = mockk()
        recurringRepository = mockk()
        ruleRepository = mockk()
        peerDebtRepository = mockk()
        contactRepository = mockk()
        // A line not shared, until a test says otherwise.
        coEvery { peerDebtRepository.getMyShare(any()) } answers {
            val line = sampleTransactions.firstOrNull { it.id == firstArg() }
            Result.success(MyShare(transactionId = firstArg(), amountCents = line?.amountCents ?: 0, canBeShared = (line?.amountCents ?: 0) < 0))
        }
        // A received line that cannot be given back, until a test says otherwise.
        coEvery { peerDebtRepository.getDebt(any()) } answers {
            val line = sampleTransactions.firstOrNull { it.id == firstArg() }
            Result.success(TransactionDebt(transactionId = firstArg(), amountCents = line?.amountCents ?: 0, canBeDebt = false))
        }
        // A line « Ce virement est » does not ask about, until a test says otherwise (MAG-453).
        coEvery { peerDebtRepository.getAllocation(any()) } answers {
            Result.success(TransactionAllocation(transactionId = firstArg(), canBeAllocated = false))
        }
        coEvery { recurringRepository.getRecords() } returns Result.success(emptyList())
        coEvery { authRepository.getUserId() } returns "user-1"
        every { mercureService.subscribe(any()) } returns emptyFlow()
        coEvery { categoryRepository.getCategories() } returns Result.success(sampleCategories)
        coEvery { transactionRepository.getTransactions(accountId) } returns pageOf(sampleTransactions)
        coEvery { transactionRepository.getIncidents(accountId) } returns Result.success(emptyList())
        coEvery { transactionRepository.getSettledIncidents(accountId) } returns Result.success(emptyList())
    }

    private fun newViewModel() =
        TransactionViewModel(
            transactionRepository, categoryRepository, accountId, mercureService, authRepository,
            recurringRepository, ruleRepository, peerDebtRepository, contactRepository,
        )

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial load fetches transactions scoped to the account`() = runTest {
        viewModel = newViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(2, state.transactions.size)
        assertFalse(state.isLoading)
        coVerify { transactionRepository.getTransactions(accountId) }
    }

    private fun lines(range: IntRange) = range.map { Transaction(id = "line-$it", label = "Ligne $it", amountCents = -it) }

    @Test
    fun `opening reads the first page only and offers the next when the account holds more`() = runTest {
        coEvery { transactionRepository.getTransactions(accountId) } returns pageOf(lines(1..30), total = 45)

        viewModel = newViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(30, state.transactions.size)
        assertTrue(state.canLoadMore)
        assertFalse(state.isLoadingMore)
        coVerify(exactly = 1) { transactionRepository.getTransactions(accountId, any()) }
    }

    @Test
    fun `loadMore appends the next page and shows the spinner while it is read`() = runTest {
        coEvery { transactionRepository.getTransactions(accountId) } returns pageOf(lines(1..30), total = 45)
        coEvery { transactionRepository.getTransactions(accountId, 2) } returns pageOf(lines(31..45), total = 45)
        viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.loadMore()
        assertTrue(viewModel.uiState.value.isLoadingMore)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals((1..45).map { "line-$it" }, state.transactions.map { it.id })
        assertFalse(state.isLoadingMore)
        assertNull(state.error)
    }

    @Test
    fun `the last page closes the list and nothing more is requested`() = runTest {
        coEvery { transactionRepository.getTransactions(accountId) } returns pageOf(lines(1..30), total = 45)
        coEvery { transactionRepository.getTransactions(accountId, 2) } returns pageOf(lines(31..45), total = 45)
        viewModel = newViewModel()
        advanceUntilIdle()
        viewModel.loadMore()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.canLoadMore)
        viewModel.loadMore()
        advanceUntilIdle()

        coVerify(exactly = 2) { transactionRepository.getTransactions(accountId, any()) }
        coVerify(exactly = 0) { transactionRepository.getTransactions(accountId, 3) }
    }

    @Test
    fun `a first page that holds everything offers no next page`() = runTest {
        viewModel = newViewModel()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.canLoadMore)
        viewModel.loadMore()
        advanceUntilIdle()

        coVerify(exactly = 1) { transactionRepository.getTransactions(accountId, any()) }
    }

    @Test
    fun `a failed next page keeps the lines, says why, and can be asked again`() = runTest {
        coEvery { transactionRepository.getTransactions(accountId) } returns pageOf(lines(1..30), total = 45)
        coEvery { transactionRepository.getTransactions(accountId, 2) } returns Result.failure(RuntimeException("offline"))
        viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.loadMore()
        advanceUntilIdle()

        var state = viewModel.uiState.value
        assertEquals(30, state.transactions.size)
        assertEquals("offline", state.error)
        assertFalse(state.isLoadingMore)
        assertTrue(state.canLoadMore)

        coEvery { transactionRepository.getTransactions(accountId, 2) } returns pageOf(lines(31..45), total = 45)
        viewModel.loadMore()
        advanceUntilIdle()

        state = viewModel.uiState.value
        assertEquals(45, state.transactions.size)
        assertFalse(state.canLoadMore)
    }

    @Test
    fun `a request already in flight is not doubled`() = runTest {
        coEvery { transactionRepository.getTransactions(accountId) } returns pageOf(lines(1..30), total = 90)
        coEvery { transactionRepository.getTransactions(accountId, 2) } returns pageOf(lines(31..60), total = 90)
        viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.loadMore()
        viewModel.loadMore()
        advanceUntilIdle()

        coVerify(exactly = 1) { transactionRepository.getTransactions(accountId, 2) }
    }

    @Test
    fun `without a total a full page may be followed by another, a short one ends the list`() = runTest {
        coEvery { transactionRepository.getTransactions(accountId) } returns pageOf(lines(1..30), total = null)
        coEvery { transactionRepository.getTransactions(accountId, 2) } returns pageOf(lines(31..40), total = null)
        viewModel = newViewModel()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.canLoadMore)

        viewModel.loadMore()
        advanceUntilIdle()

        assertEquals(40, viewModel.uiState.value.transactions.size)
        assertFalse(viewModel.uiState.value.canLoadMore)
    }

    @Test
    fun `a refresh reads again the pages already loaded so the list keeps its length`() = runTest {
        coEvery { transactionRepository.getTransactions(accountId) } returns pageOf(lines(1..30), total = 45)
        coEvery { transactionRepository.getTransactions(accountId, 2) } returns pageOf(lines(31..45), total = 45)
        viewModel = newViewModel()
        advanceUntilIdle()
        viewModel.loadMore()
        advanceUntilIdle()

        viewModel.refresh()
        advanceUntilIdle()

        assertEquals(45, viewModel.uiState.value.transactions.size)
        assertFalse(viewModel.uiState.value.canLoadMore)
        coVerify(exactly = 2) { transactionRepository.getTransactions(accountId, 2) }
    }

    @Test
    fun `a line pushed down by a new one is not listed twice`() = runTest {
        coEvery { transactionRepository.getTransactions(accountId) } returns pageOf(lines(1..30), total = 31)
        // A line arrived since: the page now starts with the last line of the first one.
        coEvery { transactionRepository.getTransactions(accountId, 2) } returns pageOf(lines(30..31), total = 31)
        viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.loadMore()
        advanceUntilIdle()

        val ids = viewModel.uiState.value.transactions.map { it.id }
        assertEquals(31, ids.size)
        assertEquals(ids.distinct(), ids)
    }

    @Test
    fun `createTransaction posts to this account with a signed amount`() = runTest {
        val request = slot<TransactionCreateRequest>()
        coEvery { transactionRepository.createTransaction(capture(request)) } returns Result.success(
            Transaction(id = "tx-3", label = "Boulangerie", amountCents = -1599),
        )
        viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.createTransaction(amountCents = -1599, label = "Boulangerie")
        advanceUntilIdle()

        assertEquals("/api/accounts/$accountId", request.captured.account)
        assertEquals(-1599, request.captured.amountCents)
        coVerify(atLeast = 2) { transactionRepository.getTransactions(accountId) }
    }

    @Test
    fun `deleteTransaction removes it from state`() = runTest {
        coEvery { transactionRepository.deleteTransaction("tx-1") } returns Result.success(Unit)
        viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.deleteTransaction("tx-1")
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.transactions.size)
        assertEquals("tx-2", viewModel.uiState.value.transactions[0].id)
    }

    @Test
    fun `categories are loaded so the form can offer them`() = runTest {
        viewModel = newViewModel()
        advanceUntilIdle()

        assertEquals(listOf("Courses", "Loisirs"), viewModel.uiState.value.categories.map { it.name })
    }

    @Test
    fun `a failing category load does not break the transaction list`() = runTest {
        coEvery { categoryRepository.getCategories() } returns Result.failure(RuntimeException("offline"))
        viewModel = newViewModel()
        advanceUntilIdle()

        assertEquals(2, viewModel.uiState.value.transactions.size)
        assertEquals(emptyList<Category>(), viewModel.uiState.value.categories)
    }

    @Test
    fun `createTransaction sends the chosen status, category and date`() = runTest {
        val request = slot<TransactionCreateRequest>()
        coEvery { transactionRepository.createTransaction(capture(request)) } returns Result.success(
            Transaction(id = "tx-4", label = "Cinéma", amountCents = -1200),
        )
        viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.createTransaction(
            amountCents = -1200,
            label = "Cinéma",
            status = "planned",
            categoryId = "cat-2",
            bookedAt = "2026-11-03",
        )
        advanceUntilIdle()

        assertEquals("planned", request.captured.status)
        assertEquals("/api/categories/cat-2", request.captured.category)
        assertEquals("2026-11-03", request.captured.bookedAt)
    }

    @Test
    fun `createTransaction without choices leaves the API defaults, spent today without category`() = runTest {
        val request = slot<TransactionCreateRequest>()
        coEvery { transactionRepository.createTransaction(capture(request)) } returns Result.success(
            Transaction(id = "tx-5", label = "Pain", amountCents = -150),
        )
        viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.createTransaction(amountCents = -150, label = "Pain")
        advanceUntilIdle()

        assertEquals("spent", request.captured.status)
        assertNull(request.captured.category)
        assertNull(request.captured.bookedAt)
    }

    @Test
    fun `only the choices made reach the wire, the API defaults do the rest`() {
        val json = kotlinx.serialization.json.Json
        val defaults = json.encodeToString(
            TransactionCreateRequest.serializer(),
            TransactionCreateRequest(account = "/api/accounts/a", amountCents = -150, label = "Pain"),
        )
        val chosen = json.encodeToString(
            TransactionCreateRequest.serializer(),
            TransactionCreateRequest(
                account = "/api/accounts/a",
                amountCents = -150,
                label = "Pain",
                status = "planned",
                bookedAt = "2026-11-03",
            ),
        )

        assertFalse(defaults.contains("status") || defaults.contains("bookedAt"))
        assertTrue(chosen.contains("\"status\":\"planned\"") && chosen.contains("\"bookedAt\":\"2026-11-03\""))
    }

    private val counterpartLeg = TransferLeg(
        id = "tx-in",
        label = "Virement du Livret",
        amountCents = 300000,
        bookedAt = "2026-09-13",
        accountId = "acc-2",
        accountName = "Courant",
    )

    private val pairedInfo = TransferInfo(transferKind = "internal", transferSource = "auto", counterpart = counterpartLeg)

    @Test
    fun `opening a line shows the detail loading, then its transfer state`() = runTest {
        coEvery { transactionRepository.getTransfer("tx-1") } returns Result.success(pairedInfo)
        viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.openDetail("tx-1")
        assertTrue(viewModel.uiState.value.detail!!.isLoading)
        advanceUntilIdle()

        val detail = viewModel.uiState.value.detail!!
        assertFalse(detail.isLoading)
        assertEquals("Courant", detail.info!!.counterpart!!.accountName)
        assertNull(detail.error)
    }

    @Test
    fun `a failing transfer load is shown on the detail, not on the list`() = runTest {
        coEvery { transactionRepository.getTransfer("tx-1") } returns Result.failure(RuntimeException("offline"))
        viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.openDetail("tx-1")
        advanceUntilIdle()

        assertEquals("offline", viewModel.uiState.value.detail!!.error)
        assertFalse(viewModel.uiState.value.detail!!.isLoading)
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `closing the detail goes back to the list`() = runTest {
        coEvery { transactionRepository.getTransfer("tx-1") } returns Result.success(TransferInfo())
        viewModel = newViewModel()
        advanceUntilIdle()
        viewModel.openDetail("tx-1")
        advanceUntilIdle()

        viewModel.closeDetail()

        assertNull(viewModel.uiState.value.detail)
    }

    @Test
    fun `marking by hand sends the chosen counterpart and shows the line as internal`() = runTest {
        coEvery { transactionRepository.getTransfer("tx-1") } returns Result.success(TransferInfo())
        coEvery { transactionRepository.setTransfer("tx-1", true, "tx-in") } returns
            Result.success(pairedInfo.copy(transferSource = "manual"))
        viewModel = newViewModel()
        advanceUntilIdle()
        viewModel.openDetail("tx-1")
        advanceUntilIdle()

        coEvery { transactionRepository.getTransactions(accountId) } returns pageOf(
            listOf(
                sampleTransactions[0].copy(transferKind = "internal", transferSource = "manual"),
                sampleTransactions[1],
            ),
        )

        viewModel.markAsTransfer("tx-in")
        advanceUntilIdle()

        val detail = viewModel.uiState.value.detail!!
        assertTrue(detail.transaction.isInternalTransfer)
        assertEquals("manual", detail.transaction.transferSource)
        assertFalse(detail.isSaving)
        coVerify { transactionRepository.setTransfer("tx-1", true, "tx-in") }
    }

    @Test
    fun `releasing a transfer frees the line and reloads the list`() = runTest {
        val marked = sampleTransactions[0].copy(transferKind = "internal")
        coEvery { transactionRepository.getTransactions(accountId) } returns pageOf(listOf(marked, sampleTransactions[1]))
        coEvery { transactionRepository.getTransfer("tx-1") } returns Result.success(pairedInfo)
        coEvery { transactionRepository.setTransfer("tx-1", false, null) } returns
            Result.success(TransferInfo(transferKind = "none", transferSource = "manual"))
        viewModel = newViewModel()
        advanceUntilIdle()
        viewModel.openDetail("tx-1")
        advanceUntilIdle()
        coEvery { transactionRepository.getTransactions(accountId) } returns pageOf(
            listOf(marked.copy(transferKind = "none", transferSource = "manual"), sampleTransactions[1]),
        )

        viewModel.releaseTransfer()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.detail!!.transaction.isInternalTransfer)
        assertFalse(viewModel.uiState.value.transactions.first { it.id == "tx-1" }.isInternalTransfer)
    }

    // MAG-350: the debit the bank rejected, and the credit on the same account that gave it back.
    private val rejectedDebit = Transaction(id = "tx-edf", label = "PRELEVEMENT EDF", amountCents = -6240, transferKind = "rejected")
    private val rejectedCredit = Transaction(id = "tx-rej", label = "REJET PRLV SEPA", amountCents = 6240, transferKind = "rejected")
    private val rejectionInfo = TransferInfo(
        transferKind = "rejected",
        transferSource = "auto",
        counterpart = TransferLeg(id = "tx-edf", label = "PRELEVEMENT EDF", amountCents = -6240, accountId = accountId),
    )

    @Test
    fun `opening a rejected credit loads the rejected payment it gave back`() = runTest {
        coEvery { transactionRepository.getTransactions(accountId) } returns
            pageOf(listOf(rejectedDebit, rejectedCredit))
        coEvery { transactionRepository.getTransfer("tx-rej") } returns Result.success(rejectionInfo)
        viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.openDetail("tx-rej")
        advanceUntilIdle()

        val detail = viewModel.uiState.value.detail!!
        assertTrue(detail.transaction.isRejected)
        assertFalse(detail.transaction.isInternalTransfer)
        assertEquals("PRELEVEMENT EDF", detail.info!!.counterpart!!.label)
        assertFalse(detail.isLoading)
    }

    @Test
    fun `releasing a rejection frees both legs and reloads the list`() = runTest {
        coEvery { transactionRepository.getTransactions(accountId) } returns
            pageOf(listOf(rejectedDebit, rejectedCredit))
        coEvery { transactionRepository.getTransfer("tx-rej") } returns Result.success(rejectionInfo)
        coEvery { transactionRepository.setTransfer("tx-rej", false, null) } returns
            Result.success(TransferInfo(transferKind = "none", transferSource = "manual"))
        viewModel = newViewModel()
        advanceUntilIdle()
        viewModel.openDetail("tx-rej")
        advanceUntilIdle()
        coEvery { transactionRepository.getTransactions(accountId) } returns pageOf(
            listOf(
                rejectedDebit.copy(transferKind = "none", transferSource = "manual"),
                rejectedCredit.copy(transferKind = "none", transferSource = "manual"),
            ),
        )

        viewModel.releaseTransfer()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.detail!!.transaction.isRejected)
        assertFalse(state.detail!!.isSaving)
        assertTrue(state.transactions.none { it.isRejected })
        coVerify { transactionRepository.setTransfer("tx-rej", false, null) }
    }

    @Test
    fun `a refused marking keeps the line as it was and says why`() = runTest {
        coEvery { transactionRepository.getTransfer("tx-1") } returns Result.success(TransferInfo())
        coEvery { transactionRepository.setTransfer("tx-1", true, "tx-in") } returns
            Result.failure(RuntimeException("Contrepartie incompatible"))
        viewModel = newViewModel()
        advanceUntilIdle()
        viewModel.openDetail("tx-1")
        advanceUntilIdle()

        viewModel.markAsTransfer("tx-in")
        advanceUntilIdle()

        val detail = viewModel.uiState.value.detail!!
        assertEquals("Contrepartie incompatible", detail.error)
        assertFalse(detail.transaction.isInternalTransfer)
        assertFalse(detail.isSaving)
    }

    @Test
    fun `the candidates load when the search opens, and a failure is reported`() = runTest {
        coEvery { transactionRepository.getTransfer("tx-1") } returns Result.success(TransferInfo())
        coEvery { transactionRepository.getTransferCandidates("tx-1") } returnsMany listOf(
            Result.success(listOf(counterpartLeg)),
            Result.failure(RuntimeException("offline")),
        )
        viewModel = newViewModel()
        advanceUntilIdle()
        viewModel.openDetail("tx-1")
        advanceUntilIdle()

        viewModel.loadCandidates()
        assertTrue(viewModel.uiState.value.detail!!.isLoading)
        advanceUntilIdle()
        assertEquals(listOf("tx-in"), viewModel.uiState.value.detail!!.candidates.map { it.id })

        viewModel.loadCandidates()
        advanceUntilIdle()
        assertEquals("offline", viewModel.uiState.value.detail!!.error)
    }

    @Test
    fun `a Mercure update on transactions refreshes the list and the open detail`() = runTest {
        val events = MutableSharedFlow<MercureEvent>()
        every { mercureService.subscribe(MercureTopics.userScoped("user-1", MercureTopics.TRANSACTIONS)) } returns events
        coEvery { transactionRepository.getTransfer("tx-1") } returns Result.success(TransferInfo())
        viewModel = newViewModel()
        advanceUntilIdle()
        viewModel.openDetail("tx-1")
        advanceUntilIdle()
        coEvery { transactionRepository.getTransfer("tx-1") } returns Result.success(pairedInfo)
        coEvery { transactionRepository.getTransactions(accountId) } returns pageOf(
            listOf(sampleTransactions[0].copy(transferKind = "internal"), sampleTransactions[1]),
        )

        events.emit(MercureEvent(data = "{}"))
        advanceUntilIdle()

        verify { mercureService.subscribe(MercureTopics.userScoped("user-1", MercureTopics.TRANSACTIONS)) }
        assertTrue(viewModel.uiState.value.transactions.first { it.id == "tx-1" }.isInternalTransfer)
        assertTrue(viewModel.uiState.value.detail!!.transaction.isInternalTransfer)
        assertEquals("Courant", viewModel.uiState.value.detail!!.info!!.counterpart!!.accountName)
    }

    @Test
    fun `a burst of Mercure updates on transactions reloads the list once`() = runTest {
        val events = MutableSharedFlow<MercureEvent>()
        every { mercureService.subscribe(MercureTopics.userScoped("user-1", MercureTopics.TRANSACTIONS)) } returns events
        viewModel = newViewModel()
        advanceUntilIdle()

        repeat(10) {
            events.emit(MercureEvent(data = "{}"))
            advanceTimeBy(20)
        }
        advanceTimeBy(10_000)

        coVerify(exactly = 2) { transactionRepository.getTransactions(accountId) }
    }

    // MAG-375: the rejected payments leave the list and make one line each on the Incidents tab.
    private val edfIncident = AccountIncident(
        debitId = "tx-edf",
        creditId = "tx-rej",
        bookedAt = "2026-09-15",
        rejectedAt = "2026-09-17",
        counterpartyName = "EDF",
        amountCents = 6240,
    )

    private val gymIncident = AccountIncident(
        debitId = "tx-gym",
        creditId = null,
        bookedAt = "2026-09-10",
        counterpartyName = "Salle de sport",
        amountCents = 3500,
    )

    /** An API that keeps the settled rejections apart, as the real one does (MAG-424). */
    private fun serveSettlements(vararg all: AccountIncident): MutableSet<String> {
        val settled = mutableSetOf<String>()
        coEvery { transactionRepository.getIncidents(accountId) } answers {
            Result.success(all.filterNot { it.openId in settled })
        }
        coEvery { transactionRepository.getSettledIncidents(accountId) } answers {
            Result.success(all.filter { it.openId in settled }.map { it.copy(settledAt = "2026-10-16T10:00:00+00:00") })
        }
        coEvery { transactionRepository.settleRejection(any()) } answers {
            settled += firstArg<String>()
            Result.success(Unit)
        }
        coEvery { transactionRepository.unsettleRejection(any()) } answers {
            settled -= firstArg<String>()
            Result.success(Unit)
        }
        return settled
    }

    @Test
    fun `settling a rejection takes it out of the pile into the settled ones and offers undo`() = runTest {
        serveSettlements(edfIncident, gymIncident)
        viewModel = newViewModel()
        advanceUntilIdle()
        assertEquals(listOf("tx-edf", "tx-gym"), viewModel.uiState.value.incidents.map { it.openId })

        viewModel.settleIncident(edfIncident)

        // At once, before the API answers: the pile shrinks and the buttons wait.
        val pending = viewModel.uiState.value
        assertEquals(listOf("tx-gym"), pending.incidents.map { it.openId })
        assertEquals(listOf("tx-edf"), pending.settledIncidents.map { it.openId })
        assertEquals(edfIncident.key, pending.settlingIncident)
        assertNull(pending.undoableSettlement)

        runCurrent()

        val done = viewModel.uiState.value
        assertNull(done.settlingIncident)
        assertEquals(edfIncident, done.undoableSettlement)
        assertEquals(listOf("tx-gym"), done.incidents.map { it.openId })
        assertEquals(listOf("tx-edf"), done.settledIncidents.map { it.openId })
        assertNotNull(done.settledIncidents.single().settledAt)
        coVerify(exactly = 1) { transactionRepository.settleRejection("tx-edf") }
    }

    @Test
    fun `undo puts the rejection back in the pile`() = runTest {
        serveSettlements(edfIncident, gymIncident)
        viewModel = newViewModel()
        advanceUntilIdle()
        viewModel.settleIncident(edfIncident)
        runCurrent()

        viewModel.undoSettlement()

        assertNull(viewModel.uiState.value.undoableSettlement)
        assertEquals(listOf("tx-edf", "tx-gym"), viewModel.uiState.value.incidents.map { it.openId })
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf("tx-edf", "tx-gym"), state.incidents.map { it.openId })
        assertTrue(state.settledIncidents.isEmpty())
        assertNull(state.settlingIncident)
        coVerify(exactly = 1) { transactionRepository.unsettleRejection("tx-edf") }
    }

    @Test
    fun `the undo offer closes after a few seconds and the rejection stays settled`() = runTest {
        serveSettlements(edfIncident)
        viewModel = newViewModel()
        advanceUntilIdle()
        viewModel.settleIncident(edfIncident)
        runCurrent()
        assertNotNull(viewModel.uiState.value.undoableSettlement)

        advanceTimeBy(UNDO_SETTLEMENT_MS + 1)

        assertNull(viewModel.uiState.value.undoableSettlement)
        viewModel.undoSettlement()
        advanceUntilIdle()
        assertEquals(listOf("tx-edf"), viewModel.uiState.value.settledIncidents.map { it.openId })
        coVerify(exactly = 0) { transactionRepository.unsettleRejection(any()) }
    }

    @Test
    fun `a settled rejection is put back from the settled section`() = runTest {
        val settled = serveSettlements(edfIncident, gymIncident)
        settled += "tx-gym"
        viewModel = newViewModel()
        advanceUntilIdle()
        assertEquals(listOf("tx-gym"), viewModel.uiState.value.settledIncidents.map { it.openId })

        viewModel.unsettleIncident(viewModel.uiState.value.settledIncidents.single())
        advanceUntilIdle()

        assertEquals(listOf("tx-edf", "tx-gym"), viewModel.uiState.value.incidents.map { it.openId })
        assertTrue(viewModel.uiState.value.settledIncidents.isEmpty())
    }

    @Test
    fun `a refused settlement puts the rejection back where it was, with the reason`() = runTest {
        serveSettlements(edfIncident, gymIncident)
        coEvery { transactionRepository.settleRejection(any()) } returns Result.failure(RuntimeException("Not a rejected payment"))
        viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.settleIncident(edfIncident)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf("tx-edf", "tx-gym"), state.incidents.map { it.openId })
        assertTrue(state.settledIncidents.isEmpty())
        assertNull(state.undoableSettlement)
        assertNull(state.settlingIncident)
        assertEquals("Not a rejected payment", state.settlementError)

        viewModel.clearSettlementError()
        assertNull(viewModel.uiState.value.settlementError)
    }

    @Test
    fun `a second tap while a settlement is on its way is ignored`() = runTest {
        serveSettlements(edfIncident, gymIncident)
        viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.settleIncident(edfIncident)
        viewModel.settleIncident(gymIncident)
        advanceUntilIdle()

        coVerify(exactly = 1) { transactionRepository.settleRejection(any()) }
        assertEquals(listOf("tx-gym"), viewModel.uiState.value.incidents.map { it.openId })
    }

    @Test
    fun `incidents are loading until the API answers`() = runTest {
        viewModel = newViewModel()

        assertTrue(viewModel.uiState.value.incidents.isEmpty())
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isLoadingIncidents)
    }

    @Test
    fun `the incidents of the account are loaded with the list`() = runTest {
        coEvery { transactionRepository.getIncidents(accountId) } returns Result.success(listOf(edfIncident))
        viewModel = newViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf(edfIncident), state.incidents)
        assertFalse(state.isLoadingIncidents)
        assertNull(state.incidentsError)
        assertEquals(2, state.transactions.size)
        coVerify { transactionRepository.getIncidents(accountId) }
    }

    @Test
    fun `an account with no rejection has an empty incident list and no error`() = runTest {
        viewModel = newViewModel()
        advanceUntilIdle()

        assertEquals(emptyList<AccountIncident>(), viewModel.uiState.value.incidents)
        assertNull(viewModel.uiState.value.incidentsError)
    }

    @Test
    fun `unreadable incidents are an error of their own, the transaction list stays`() = runTest {
        coEvery { transactionRepository.getIncidents(accountId) } returns Result.failure(RuntimeException("offline"))
        viewModel = newViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("offline", state.incidentsError)
        assertFalse(state.isLoadingIncidents)
        assertNull(state.error)
        assertEquals(2, state.transactions.size)
    }

    @Test
    fun `a rejection detected while the screen is open reaches the incidents on the Mercure message`() = runTest {
        val events = MutableSharedFlow<MercureEvent>()
        every { mercureService.subscribe(any()) } returns events
        viewModel = newViewModel()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.incidents.isEmpty())

        coEvery { transactionRepository.getIncidents(accountId) } returns Result.success(listOf(edfIncident))
        events.emit(MercureEvent(data = "{}"))
        advanceTimeBy(5_000)
        advanceUntilIdle()

        assertEquals(listOf(edfIncident), viewModel.uiState.value.incidents)
    }

    @Test
    fun `opening an incident opens its payment, which names the credit that gave it back`() = runTest {
        coEvery { transactionRepository.getIncidents(accountId) } returns Result.success(listOf(edfIncident))
        coEvery { transactionRepository.getTransfer("tx-edf") } returns Result.success(
            TransferInfo(
                transferKind = "rejected",
                transferSource = "auto",
                counterpart = TransferLeg(id = "tx-rej", label = "REJET PRLV SEPA", amountCents = 6240, accountId = accountId),
            ),
        )
        viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.openIncident(edfIncident)
        assertTrue(viewModel.uiState.value.detail!!.isLoading)
        advanceUntilIdle()

        val detail = viewModel.uiState.value.detail!!
        assertEquals("tx-edf", detail.transaction.id)
        assertEquals(-6240, detail.transaction.amountCents)
        assertTrue(detail.transaction.isRejected)
        assertEquals("REJET PRLV SEPA", detail.info!!.counterpart!!.label)
    }

    @Test
    fun `an incident whose payment is not on the account opens its credit`() = runTest {
        val creditOnly = edfIncident.copy(debitId = null)
        coEvery { transactionRepository.getTransfer("tx-rej") } returns Result.success(TransferInfo(transferKind = "rejected"))
        viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.openIncident(creditOnly)
        advanceUntilIdle()

        assertEquals("tx-rej", viewModel.uiState.value.detail!!.transaction.id)
        assertEquals(6240, viewModel.uiState.value.detail!!.transaction.amountCents)
    }

    private val mandatoryRepayment = Qualification(mandatory = true, kind = QualificationKind.Repayment)

    @Test
    fun `qualifying the open line sends category and qualification together and takes the answer`() = runTest {
        coEvery { transactionRepository.getTransfer("tx-1") } returns Result.success(TransferInfo())
        val qualified = sampleTransactions[0].copy(
            category = "/api/categories/cat-2",
            qualification = mandatoryRepayment,
            qualifiedBy = "manual",
        )
        var served = sampleTransactions
        coEvery { transactionRepository.getTransactions(accountId) } answers { pageOf(served) }
        coEvery { transactionRepository.setQualification("tx-1", "cat-2", mandatoryRepayment) } answers {
            served = listOf(qualified, sampleTransactions[1])
            Result.success(qualified)
        }
        viewModel = newViewModel()
        advanceUntilIdle()
        viewModel.openDetail("tx-1")
        advanceUntilIdle()

        viewModel.setQualification("cat-2", mandatoryRepayment)
        advanceUntilIdle()

        val detail = viewModel.uiState.value.detail!!
        assertEquals("cat-2", detail.transaction.categoryId)
        assertEquals(mandatoryRepayment, detail.transaction.qualification)
        assertEquals("manual", detail.transaction.qualifiedBy)
        assertFalse(detail.isSaving)
        assertNull(detail.error)
        coVerify(atLeast = 2) { transactionRepository.getTransactions(accountId) }
    }

    @Test
    fun `changing only the qualification keeps the category in the same call`() = runTest {
        coEvery { transactionRepository.getTransfer("tx-1") } returns Result.success(TransferInfo())
        val filed = sampleTransactions[0].copy(category = "/api/categories/cat-1")
        val qualified = filed.copy(qualification = mandatoryRepayment, qualifiedBy = "manual")
        var served = listOf(filed, sampleTransactions[1])
        coEvery { transactionRepository.getTransactions(accountId) } answers { pageOf(served) }
        coEvery { transactionRepository.setQualification("tx-1", "cat-1", mandatoryRepayment) } answers {
            served = listOf(qualified, sampleTransactions[1])
            Result.success(qualified)
        }
        viewModel = newViewModel()
        advanceUntilIdle()
        viewModel.openDetail("tx-1")
        advanceUntilIdle()

        viewModel.setQualification("cat-1", mandatoryRepayment)
        advanceUntilIdle()

        coVerify { transactionRepository.setQualification("tx-1", "cat-1", mandatoryRepayment) }
        assertEquals(mandatoryRepayment, viewModel.uiState.value.detail!!.transaction.qualification)
    }

    @Test
    fun `a refused qualification says why on the detail and keeps the line as it was`() = runTest {
        coEvery { transactionRepository.getTransfer("tx-1") } returns Result.success(TransferInfo())
        coEvery { transactionRepository.setQualification("tx-1", "cat-2", mandatoryRepayment) } returns
            Result.failure(RuntimeException("422"))
        viewModel = newViewModel()
        advanceUntilIdle()
        viewModel.openDetail("tx-1")
        advanceUntilIdle()

        viewModel.setQualification("cat-2", mandatoryRepayment)
        advanceUntilIdle()

        val detail = viewModel.uiState.value.detail!!
        assertEquals("422", detail.error)
        assertNull(detail.transaction.categoryId)
        assertEquals(Qualification.NONE, detail.transaction.qualification)
        assertFalse(detail.isSaving)
    }

    @Test
    fun `qualifying with no line open does nothing`() = runTest {
        viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.setQualification("cat-2", mandatoryRepayment)
        advanceUntilIdle()

        coVerify(exactly = 0) { transactionRepository.setQualification(any(), any(), any()) }
    }

    @Test
    fun `opening a line set by a rule reads the rule names once, for its provenance`() = runTest {
        val byRule = sampleTransactions[0].copy(qualifiedBy = "rule", qualifiedByRule = "/api/categorization_rules/r1")
        coEvery { transactionRepository.getTransactions(accountId) } returns pageOf(listOf(byRule, sampleTransactions[1]))
        coEvery { transactionRepository.getTransfer("tx-1") } returns Result.success(TransferInfo())
        coEvery { ruleRepository.getRules() } returns Result.success(listOf(CategorizationRule(id = "r1", labelPattern = "SNCF")))
        viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.openDetail("tx-1")
        advanceUntilIdle()

        assertEquals(mapOf("r1" to "SNCF"), viewModel.uiState.value.ruleNames)
        coVerify(exactly = 1) { ruleRepository.getRules() }
    }

    @Test
    fun `a line not set by a rule does not read the rules`() = runTest {
        coEvery { transactionRepository.getTransfer("tx-1") } returns Result.success(TransferInfo())
        viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.openDetail("tx-1")
        advanceUntilIdle()

        coVerify(exactly = 0) { ruleRepository.getRules() }
    }

    @Test
    fun `a line created with a qualification sends it, one without leaves it out`() = runTest {
        val requests = mutableListOf<TransactionCreateRequest>()
        coEvery { transactionRepository.createTransaction(capture(requests)) } returns Result.success(
            Transaction(id = "tx-3", label = "Crédit auto", amountCents = -35000),
        )
        viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.createTransaction(amountCents = -35000, label = "Crédit auto", qualification = mandatoryRepayment)
        viewModel.createTransaction(amountCents = -1599, label = "Boulangerie")
        advanceUntilIdle()

        assertEquals(mandatoryRepayment, requests[0].qualification)
        assertNull(requests[1].qualification)
    }

    // MAG-464: the single sheet — one gesture, one transition, one test.

    private fun kotlinx.coroutines.test.TestScope.openSheet(id: String = "tx-1") {
        coEvery { transactionRepository.getTransfer(id) } returns Result.success(TransferInfo())
        viewModel = newViewModel()
        advanceUntilIdle()
        viewModel.openDetail(id)
        advanceUntilIdle()
    }

    @Test
    fun `the series names are read once so the pills can name the series`() = runTest {
        coEvery { recurringRepository.getRecords() } returns Result.success(
            listOf(RecurringOperationRecord(id = "s1", label = "Loyer")),
        )
        viewModel = newViewModel()
        advanceUntilIdle()

        assertEquals(mapOf("s1" to "Loyer"), viewModel.uiState.value.seriesNames)
    }

    @Test
    fun `unreadable series names leave the list working`() = runTest {
        coEvery { recurringRepository.getRecords() } returns Result.failure(RuntimeException("offline"))
        viewModel = newViewModel()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.seriesNames.isEmpty())
        assertEquals(2, viewModel.uiState.value.transactions.size)
    }

    @Test
    fun `writing a note saves it at once and the sheet shows it`() = runTest {
        coEvery { transactionRepository.setNote("tx-1", "Cadeau Léa") } returns
            Result.success(sampleTransactions[0].copy(note = "Cadeau Léa"))
        openSheet()

        viewModel.setNote("Cadeau Léa")
        advanceUntilIdle()

        val detail = viewModel.uiState.value.detail!!
        assertEquals("Cadeau Léa", detail.transaction.note)
        assertFalse(detail.isSaving)
        assertNull(detail.error)
    }

    @Test
    fun `erasing a note clears it on the sheet`() = runTest {
        coEvery { transactionRepository.getTransactions(accountId) } returns pageOf(
            listOf(sampleTransactions[0].copy(note = "Ancienne"), sampleTransactions[1]),
        )
        coEvery { transactionRepository.setNote("tx-1", "") } returns Result.success(sampleTransactions[0])
        openSheet()
        assertEquals("Ancienne", viewModel.uiState.value.detail!!.transaction.note)

        viewModel.setNote("")
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.detail!!.transaction.note)
    }

    @Test
    fun `a refused note says why and stops saving`() = runTest {
        coEvery { transactionRepository.setNote("tx-1", "x") } returns Result.failure(RuntimeException("note is at most 2000 characters."))
        openSheet()

        viewModel.setNote("x")
        advanceUntilIdle()

        val detail = viewModel.uiState.value.detail!!
        assertEquals("note is at most 2000 characters.", detail.error)
        assertFalse(detail.isSaving)
        assertNull(detail.transaction.note)
    }

    private val attached = sampleTransactions[0].copy(
        recurringOperation = "/api/recurring_operations/s1",
        recurringOccurrenceOn = "2026-09-05",
    )

    @Test
    fun `detaching from the series frees the line and reloads the list`() = runTest {
        var served = listOf(attached, sampleTransactions[1])
        coEvery { transactionRepository.getTransactions(accountId) } answers { pageOf(served) }
        coEvery { recurringRepository.detachTransaction("tx-1") } answers {
            served = sampleTransactions
            Result.success(Unit)
        }
        openSheet()
        assertTrue(viewModel.uiState.value.detail!!.transaction.isAttachedToRecurring)

        viewModel.detachFromSeries()
        advanceUntilIdle()

        val detail = viewModel.uiState.value.detail!!
        assertFalse(detail.transaction.isAttachedToRecurring)
        assertNull(detail.transaction.recurringOccurrenceOn)
        assertFalse(detail.isSaving)
        assertFalse(viewModel.uiState.value.transactions.first { it.id == "tx-1" }.isAttachedToRecurring)
    }

    @Test
    fun `a refused detach keeps the line attached and says why`() = runTest {
        coEvery { transactionRepository.getTransactions(accountId) } returns pageOf(listOf(attached, sampleTransactions[1]))
        coEvery { recurringRepository.detachTransaction("tx-1") } returns Result.failure(RuntimeException("offline"))
        openSheet()

        viewModel.detachFromSeries()
        advanceUntilIdle()

        val detail = viewModel.uiState.value.detail!!
        assertEquals("offline", detail.error)
        assertTrue(detail.transaction.isAttachedToRecurring)
        assertFalse(detail.isSaving)
    }

    @Test
    fun `marking a line internal leaves only its nature and its note on the sheet`() = runTest {
        coEvery { transactionRepository.setTransfer("tx-1", true, null) } returns
            Result.success(TransferInfo(transferKind = "internal", transferSource = "manual"))
        openSheet()
        assertEquals(6, visibleSheetSections(viewModel.uiState.value.detail!!.transaction.transferKind).size)
        coEvery { transactionRepository.getTransactions(accountId) } returns pageOf(
            listOf(sampleTransactions[0].copy(transferKind = "internal"), sampleTransactions[1]),
        )

        viewModel.markAsTransfer(null)
        advanceUntilIdle()

        val kind = viewModel.uiState.value.detail!!.transaction.transferKind
        assertEquals(listOf(TransactionSheetSection.Nature, TransactionSheetSection.Note), visibleSheetSections(kind))
    }

    @Test
    fun `releasing a line brings its sections back`() = runTest {
        val internal = sampleTransactions[0].copy(transferKind = "internal")
        coEvery { transactionRepository.getTransactions(accountId) } returns pageOf(listOf(internal, sampleTransactions[1]))
        coEvery { transactionRepository.setTransfer("tx-1", false, null) } returns
            Result.success(TransferInfo(transferKind = "none", transferSource = "manual"))
        openSheet()
        assertEquals(2, visibleSheetSections(viewModel.uiState.value.detail!!.transaction.transferKind).size)
        coEvery { transactionRepository.getTransactions(accountId) } returns pageOf(sampleTransactions)

        viewModel.releaseTransfer()
        advanceUntilIdle()

        assertEquals(6, visibleSheetSections(viewModel.uiState.value.detail!!.transaction.transferKind).size)
    }

    private val drawn = RulePreview(
        total = 2,
        changeCount = 2,
        matches = listOf(
            RulePreviewMatch("tx-1", "Supermarché", -4599, "2026-09-12", wouldChange = true),
            RulePreviewMatch("tx-9", "Supermarché", -1200, "2026-08-02", wouldChange = true),
        ),
    )

    @Test
    fun `opening the filter fills it from the line and lists what it catches`() = runTest {
        val requests = mutableListOf<RulePreviewRequest>()
        coEvery { ruleRepository.previewRule(capture(requests)) } returns Result.success(drawn)
        openSheet()

        viewModel.openRuleDraft()
        assertTrue(viewModel.uiState.value.ruleDraft!!.isPreviewing)
        advanceUntilIdle()

        val draft = viewModel.uiState.value.ruleDraft!!
        assertEquals("Supermarché", draft.labelPattern)
        assertEquals("debit", draft.direction)
        assertNull(draft.categoryId)
        assertEquals(drawn, draft.preview)
        assertFalse(draft.isPreviewing)
        assertEquals("debit", requests.single().direction)
    }

    @Test
    fun `the filter of an income takes the credit direction and the category of the line`() = runTest {
        val salary = sampleTransactions[1].copy(category = "/api/categories/cat-1")
        coEvery { transactionRepository.getTransactions(accountId) } returns pageOf(listOf(sampleTransactions[0], salary))
        coEvery { ruleRepository.previewRule(any()) } returns Result.success(RulePreview())
        openSheet("tx-2")

        viewModel.openRuleDraft()
        advanceUntilIdle()

        val draft = viewModel.uiState.value.ruleDraft!!
        assertEquals("credit", draft.direction)
        assertEquals("cat-1", draft.categoryId)
    }

    @Test
    fun `changing the pattern asks for a new preview once typing pauses, and only the last one counts`() = runTest {
        val requests = mutableListOf<String>()
        coEvery { ruleRepository.previewRule(any()) } answers {
            requests += firstArg<RulePreviewRequest>().labelPattern.orEmpty()
            Result.success(drawn.copy(total = requests.size))
        }
        openSheet()
        viewModel.openRuleDraft()
        advanceUntilIdle()
        requests.clear()

        viewModel.changeRulePattern("Super")
        advanceTimeBy(100)
        viewModel.changeRulePattern("Supermarc")
        advanceUntilIdle()

        assertEquals(listOf("Supermarc"), requests)
        assertEquals("Supermarc", viewModel.uiState.value.ruleDraft!!.labelPattern)
        assertEquals(1, viewModel.uiState.value.ruleDraft!!.preview!!.total)
    }

    @Test
    fun `a blank pattern asks for nothing and empties the list`() = runTest {
        coEvery { ruleRepository.previewRule(any()) } returns Result.success(drawn)
        openSheet()
        viewModel.openRuleDraft()
        advanceUntilIdle()

        viewModel.changeRulePattern("  ")
        advanceUntilIdle()

        val draft = viewModel.uiState.value.ruleDraft!!
        assertNull(draft.preview)
        assertFalse(draft.isPreviewing)
        coVerify(exactly = 1) { ruleRepository.previewRule(any()) }
    }

    @Test
    fun `a failing preview is reported on the filter, not on the list`() = runTest {
        coEvery { ruleRepository.previewRule(any()) } returns Result.failure(RuntimeException("L'aperçu est indisponible"))
        openSheet()

        viewModel.openRuleDraft()
        advanceUntilIdle()

        assertEquals("L'aperçu est indisponible", viewModel.uiState.value.ruleDraft!!.error)
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `choosing the filter category previews again with it`() = runTest {
        val requests = mutableListOf<RulePreviewRequest>()
        coEvery { ruleRepository.previewRule(capture(requests)) } returns Result.success(drawn)
        openSheet()
        viewModel.openRuleDraft()
        advanceUntilIdle()

        viewModel.changeRuleCategory("cat-1")
        advanceUntilIdle()

        assertEquals("cat-1", viewModel.uiState.value.ruleDraft!!.categoryId)
        assertEquals("cat-1", requests.last().categoryId)
    }

    @Test
    fun `saving the filter creates the rule, closes the screen and reloads the list`() = runTest {
        val created = slot<CategorizationRuleCreateRequest>()
        coEvery { ruleRepository.previewRule(any()) } returns Result.success(drawn)
        coEvery { ruleRepository.createRule(capture(created)) } returns
            Result.success(CategorizationRule(id = "r1", labelPattern = "Supermarché"))
        openSheet()
        viewModel.openRuleDraft()
        viewModel.changeRuleCategory("cat-1")
        viewModel.changeRuleQualification(mandatoryRepayment)
        advanceUntilIdle()

        viewModel.saveRule()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.ruleDraft)
        assertEquals("Supermarché", created.captured.labelPattern)
        assertEquals("/api/categories/cat-1", created.captured.category)
        assertEquals(mandatoryRepayment, created.captured.qualification)
        assertEquals("debit", created.captured.direction)
        coVerify(atLeast = 2) { transactionRepository.getTransactions(accountId) }
    }

    @Test
    fun `a refused filter stays open with the reason and stops saving`() = runTest {
        coEvery { ruleRepository.previewRule(any()) } returns Result.success(drawn)
        coEvery { ruleRepository.createRule(any()) } returns Result.failure(RuntimeException("422"))
        openSheet()
        viewModel.openRuleDraft()
        viewModel.changeRuleCategory("cat-1")
        advanceUntilIdle()

        viewModel.saveRule()
        advanceUntilIdle()

        val draft = viewModel.uiState.value.ruleDraft!!
        assertEquals("422", draft.error)
        assertFalse(draft.isSaving)
    }

    @Test
    fun `a filter without a category is not saved`() = runTest {
        coEvery { ruleRepository.previewRule(any()) } returns Result.success(drawn)
        openSheet()
        viewModel.openRuleDraft()
        advanceUntilIdle()

        viewModel.saveRule()
        advanceUntilIdle()

        coVerify(exactly = 0) { ruleRepository.createRule(any()) }
        assertEquals("Supermarché", viewModel.uiState.value.ruleDraft!!.labelPattern)
    }

    @Test
    fun `without a label and without an amount the filter is refused and previews nothing`() = runTest {
        coEvery { ruleRepository.previewRule(any()) } returns Result.success(drawn)
        openSheet()
        viewModel.openRuleDraft()
        viewModel.changeRuleCategory("cat-1")
        advanceUntilIdle()

        viewModel.changeRulePattern("")
        advanceUntilIdle()
        viewModel.saveRule()
        advanceUntilIdle()

        val draft = viewModel.uiState.value.ruleDraft!!
        assertNotNull(draft.problem)
        assertNull(draft.preview)
        coVerify(exactly = 0) { ruleRepository.createRule(any()) }
    }

    @Test
    fun `a direction and an amount range are enough, the label is sent as null`() = runTest {
        val previews = mutableListOf<RulePreviewRequest>()
        val created = slot<CategorizationRuleCreateRequest>()
        coEvery { ruleRepository.previewRule(capture(previews)) } returns Result.success(drawn)
        coEvery { ruleRepository.createRule(capture(created)) } returns Result.success(CategorizationRule(id = "r1"))
        val salary = sampleTransactions[1].copy(amountCents = 465000)
        coEvery { transactionRepository.getTransactions(accountId) } returns pageOf(listOf(sampleTransactions[0], salary))
        openSheet("tx-2")
        viewModel.openRuleDraft()
        viewModel.changeRuleCategory("cat-1")
        advanceUntilIdle()

        viewModel.changeRulePattern("  ")
        viewModel.changeRuleAmount("4 750", "10")
        advanceUntilIdle()

        val draft = viewModel.uiState.value.ruleDraft!!
        assertNull(draft.problem)
        assertEquals(427500 to 522500, draft.amountBounds)
        assertNull(previews.last().labelPattern)
        assertEquals("credit", previews.last().direction)
        assertEquals(427500, previews.last().minAmountCents)
        assertEquals(522500, previews.last().maxAmountCents)

        viewModel.saveRule()
        advanceUntilIdle()

        assertNull(created.captured.labelPattern)
        assertEquals("credit", created.captured.direction)
        assertEquals(427500, created.captured.minAmountCents)
        assertEquals(522500, created.captured.maxAmountCents)
        assertNull(viewModel.uiState.value.ruleDraft)
    }

    @Test
    fun `an unreadable amount or tolerance blocks the filter even with a label`() = runTest {
        coEvery { ruleRepository.previewRule(any()) } returns Result.success(drawn)
        openSheet()
        viewModel.openRuleDraft()
        viewModel.changeRuleCategory("cat-1")
        advanceUntilIdle()

        viewModel.changeRuleAmount("abc", "10")
        advanceUntilIdle()
        assertNotNull(viewModel.uiState.value.ruleDraft!!.problem)

        viewModel.changeRuleAmount("100", "150")
        advanceUntilIdle()
        assertNotNull(viewModel.uiState.value.ruleDraft!!.problem)

        viewModel.changeRuleAmount("", "10")
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.ruleDraft!!.problem)
    }

    @Test
    fun `closing the filter goes back to the sheet`() = runTest {
        coEvery { ruleRepository.previewRule(any()) } returns Result.success(drawn)
        openSheet()
        viewModel.openRuleDraft()
        advanceUntilIdle()

        viewModel.closeRuleDraft()

        assertNull(viewModel.uiState.value.ruleDraft)
        assertEquals("tx-1", viewModel.uiState.value.detail!!.transaction.id)
    }

    private val hellfest = Envelope(id = "env-hellfest", mode = "one_off", amountCents = 40000, name = "Hellfest 2026")

    @Test
    fun `opening the choice reads the one-off envelopes`() = runTest {
        coEvery { transactionRepository.getTransfer("tx-1") } returns Result.success(TransferInfo())
        coEvery { transactionRepository.getOneOffEnvelopes() } returns Result.success(listOf(hellfest))
        viewModel = newViewModel()
        advanceUntilIdle()
        viewModel.openDetail("tx-1")
        advanceUntilIdle()

        viewModel.loadOneOffEnvelopes()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf(hellfest), state.oneOffEnvelopes)
        assertFalse(state.isLoadingOneOffEnvelopes)
    }

    @Test
    fun `a line already attributed names its event once opened`() = runTest {
        val attributed = sampleTransactions[0].copy(oneOffEnvelope = "/api/envelopes/env-hellfest")
        coEvery { transactionRepository.getTransactions(accountId) } returns pageOf(listOf(attributed, sampleTransactions[1]))
        coEvery { transactionRepository.getTransfer("tx-1") } returns Result.success(TransferInfo())
        coEvery { transactionRepository.getOneOffEnvelopes() } returns Result.success(listOf(hellfest))
        viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.openDetail("tx-1")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("Hellfest 2026", state.oneOffEnvelopeNameOf(state.detail!!.transaction))
    }

    @Test
    fun `opening a line not attributed reads no envelope`() = runTest {
        coEvery { transactionRepository.getTransfer("tx-1") } returns Result.success(TransferInfo())
        viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.openDetail("tx-1")
        advanceUntilIdle()

        coVerify(exactly = 0) { transactionRepository.getOneOffEnvelopes() }
        assertNull(viewModel.uiState.value.oneOffEnvelopeNameOf(viewModel.uiState.value.detail!!.transaction))
    }

    @Test
    fun `attributing the open spend to a one-off envelope puts it on the detail and refreshes the list`() = runTest {
        coEvery { transactionRepository.getTransfer("tx-1") } returns Result.success(TransferInfo())
        val attributed = sampleTransactions[0].copy(oneOffEnvelope = "/api/envelopes/env-hellfest")
        var served = sampleTransactions
        coEvery { transactionRepository.getTransactions(accountId) } answers { pageOf(served) }
        coEvery { transactionRepository.setOneOffEnvelope("tx-1", "env-hellfest") } answers {
            served = listOf(attributed, sampleTransactions[1])
            Result.success(attributed)
        }
        viewModel = newViewModel()
        advanceUntilIdle()
        viewModel.openDetail("tx-1")
        advanceUntilIdle()

        viewModel.setOneOffEnvelope("env-hellfest")
        advanceUntilIdle()

        val detail = viewModel.uiState.value.detail!!
        assertEquals("env-hellfest", detail.transaction.oneOffEnvelopeId)
        assertFalse(detail.isSaving)
        assertNull(detail.error)
        coVerify(atLeast = 2) { transactionRepository.getTransactions(accountId) }
    }

    @Test
    fun `taking the attribution back clears it on the detail`() = runTest {
        val attributed = sampleTransactions[0].copy(oneOffEnvelope = "/api/envelopes/env-hellfest")
        var served = listOf(attributed, sampleTransactions[1])
        coEvery { transactionRepository.getTransactions(accountId) } answers { pageOf(served) }
        coEvery { transactionRepository.getTransfer("tx-1") } returns Result.success(TransferInfo())
        coEvery { transactionRepository.getOneOffEnvelopes() } returns Result.success(listOf(hellfest))
        coEvery { transactionRepository.setOneOffEnvelope("tx-1", null) } answers {
            served = sampleTransactions
            Result.success(sampleTransactions[0])
        }
        viewModel = newViewModel()
        advanceUntilIdle()
        viewModel.openDetail("tx-1")
        advanceUntilIdle()

        viewModel.setOneOffEnvelope(null)
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.detail!!.transaction.oneOffEnvelope)
        coVerify { transactionRepository.setOneOffEnvelope("tx-1", null) }
    }

    @Test
    fun `a refused attribution says why on the detail and keeps the line as it was`() = runTest {
        coEvery { transactionRepository.getTransfer("tx-1") } returns Result.success(TransferInfo())
        coEvery { transactionRepository.setOneOffEnvelope("tx-1", "env-monthly") } returns Result.failure(RuntimeException("400"))
        viewModel = newViewModel()
        advanceUntilIdle()
        viewModel.openDetail("tx-1")
        advanceUntilIdle()

        viewModel.setOneOffEnvelope("env-monthly")
        advanceUntilIdle()

        val detail = viewModel.uiState.value.detail!!
        assertEquals("400", detail.error)
        assertNull(detail.transaction.oneOffEnvelope)
        assertFalse(detail.isSaving)
    }

    @Test
    fun `a failed read of the envelopes says why on the detail`() = runTest {
        coEvery { transactionRepository.getTransfer("tx-1") } returns Result.success(TransferInfo())
        coEvery { transactionRepository.getOneOffEnvelopes() } returns Result.failure(RuntimeException("Réseau indisponible"))
        viewModel = newViewModel()
        advanceUntilIdle()
        viewModel.openDetail("tx-1")
        advanceUntilIdle()

        viewModel.loadOneOffEnvelopes()
        advanceUntilIdle()

        assertEquals("Réseau indisponible", viewModel.uiState.value.detail!!.error)
        assertFalse(viewModel.uiState.value.isLoadingOneOffEnvelopes)
    }

    // « Partage » (MAG-451): the gîte of 800 €, my part 200 €, Julie, Tom and Inès 200 € each.

    private val gite = Transaction(id = "tx-gite", label = "GITE DES CIMES", amountCents = -80000)
    private val julie = Contact(id = "c-julie", name = "Julie")
    private val tom = Contact(id = "c-tom", name = "Tom")
    private val ines = Contact(id = "c-ines", name = "Inès")

    private fun sharedGite(myShareCents: Int, vararg people: Pair<Contact, Int>) = MyShare(
        transactionId = "tx-gite",
        amountCents = -80000,
        myShareCents = myShareCents,
        canBeShared = true,
        peerDebt = PeerDebt(
            id = "debt-1",
            `object` = "Gîte des Cimes",
            notes = "Week-end d'octobre",
            originTransactionId = "tx-gite",
            amountCents = 80000 - myShareCents,
            remainingCents = 80000 - myShareCents,
            shares = people.mapIndexed { i, (contact, cents) ->
                PeerDebtShare(id = "s-$i", contactId = contact.id, contactName = contact.name, amountCents = cents, remainingCents = cents)
            },
        ),
    )

    /** The gîte's sheet open, not shared yet: the editor read from the server, switched off. */
    private fun kotlinx.coroutines.test.TestScope.openGite(share: MyShare? = null) {
        coEvery { transactionRepository.getTransactions(accountId) } returns pageOf(listOf(gite.copy(myShareCents = share?.myShareCents)))
        coEvery { transactionRepository.getTransfer("tx-gite") } returns Result.success(TransferInfo())
        coEvery { peerDebtRepository.getMyShare("tx-gite") } returns
            Result.success(share ?: MyShare(transactionId = "tx-gite", amountCents = -80000, canBeShared = true))
        viewModel = newViewModel()
        advanceUntilIdle()
        viewModel.openDetail("tx-gite")
        advanceUntilIdle()
    }

    private val share get() = viewModel.uiState.value.detail!!.share!!

    @Test
    fun `opening a spend reads its share, switched off while it is not shared`() = runTest {
        openGite()

        assertFalse(share.enabled)
        assertTrue(share.canBeShared)
        assertEquals(80000, share.totalCents)
        assertEquals("GITE DES CIMES", share.subject)
        coVerify { peerDebtRepository.getMyShare("tx-gite") }
    }

    @Test
    fun `an internal transfer has no share to read`() = runTest {
        coEvery { transactionRepository.getTransactions(accountId) } returns
            pageOf(listOf(gite.copy(transferKind = "internal")))
        coEvery { transactionRepository.getTransfer("tx-gite") } returns Result.success(TransferInfo(transferKind = "internal"))
        viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.openDetail("tx-gite")
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.detail!!.share)
        coVerify(exactly = 0) { peerDebtRepository.getMyShare(any()) }
    }

    @Test
    fun `a shared spend opens switched on, with its people and its object`() = runTest {
        openGite(sharedGite(20000, julie to 20000, tom to 20000, ines to 20000))

        assertTrue(share.enabled)
        assertTrue(share.isShared)
        assertEquals(20000, share.myShareCents)
        assertEquals(listOf("Julie", "Tom", "Inès"), share.people.map { it.name })
        assertFalse("an equal split is not an edit by hand", share.editedByHand)
        assertEquals("Gîte des Cimes", share.subject)
        assertEquals("Week-end d'octobre", share.notes)
    }

    @Test
    fun `switching the share on proposes half, owed by nobody yet`() = runTest {
        openGite()

        viewModel.setShareEnabled(true)

        assertTrue(share.enabled)
        assertEquals(40000, share.myShareCents)
        assertEquals(40000, share.owedCents)
        assertEquals("Ajoute au moins une personne qui te doit sa part.", share.problem)
        assertFalse(share.canSave)
    }

    @Test
    fun `adding people splits what is owed equally, the last cent on the last`() = runTest {
        openGite()
        viewModel.setShareEnabled(true)
        viewModel.changeMyShare(20000)

        viewModel.addSharePerson(julie)
        viewModel.addSharePerson(tom)
        viewModel.addSharePerson(ines)

        assertEquals(listOf(20000, 20000, 20000), share.people.map { it.amountCents })
        assertEquals(60000, share.owedCents)
        assertTrue(share.canSave)

        viewModel.changeMyShare(20001)
        assertEquals(listOf(19999, 19999, 20001), share.people.map { it.amountCents })
    }

    @Test
    fun `the same person cannot be added twice`() = runTest {
        openGite()
        viewModel.setShareEnabled(true)

        viewModel.addSharePerson(tom)
        viewModel.addSharePerson(tom)

        assertEquals(listOf("Tom"), share.people.map { it.name })
    }

    @Test
    fun `changing my part splits the rest again`() = runTest {
        openGite()
        viewModel.setShareEnabled(true)
        viewModel.addSharePerson(julie)
        viewModel.addSharePerson(tom)

        viewModel.changeMyShare(20000)

        assertEquals(60000, share.owedCents)
        assertEquals(listOf(30000, 30000), share.people.map { it.amountCents })
    }

    @Test
    fun `my part stays between zero and the spend, exclusive`() = runTest {
        openGite()
        viewModel.setShareEnabled(true)

        viewModel.changeMyShare(-100)
        assertEquals(0, share.myShareCents)

        viewModel.changeMyShare(80000)
        assertEquals(79999, share.myShareCents)
    }

    @Test
    fun `removing a person splits between those left`() = runTest {
        openGite()
        viewModel.setShareEnabled(true)
        viewModel.changeMyShare(20000)
        viewModel.addSharePerson(julie)
        viewModel.addSharePerson(tom)
        viewModel.addSharePerson(ines)

        viewModel.removeSharePerson("c-tom")

        assertEquals(listOf("Julie", "Inès"), share.people.map { it.name })
        assertEquals(listOf(30000, 30000), share.people.map { it.amountCents })
    }

    @Test
    fun `an amount edited by hand stops the split and must add up`() = runTest {
        openGite()
        viewModel.setShareEnabled(true)
        viewModel.changeMyShare(20000)
        viewModel.addSharePerson(julie)
        viewModel.addSharePerson(tom)

        viewModel.editShareAmount("c-julie", 40000)

        assertTrue(share.editedByHand)
        assertEquals(listOf(40000, 30000), share.people.map { it.amountCents })
        assertNotNull(share.problem)
        assertFalse(share.canSave)

        // My part moving no longer touches their amounts.
        viewModel.changeMyShare(10000)
        assertEquals(listOf(40000, 30000), share.people.map { it.amountCents })

        viewModel.editShareAmount("c-tom", 30000)
        viewModel.changeMyShare(10000)
        assertEquals(listOf(40000, 30000), share.people.map { it.amountCents })
        assertTrue(share.canSave)
    }

    @Test
    fun `saving an equal split sends no amount, and the line becomes shared`() = runTest {
        val request = slot<MyShareRequest>()
        coEvery { peerDebtRepository.setMyShare("tx-gite", capture(request)) } returns
            Result.success(sharedGite(20000, julie to 20000, tom to 20000, ines to 20000))
        openGite()
        viewModel.setShareEnabled(true)
        viewModel.changeMyShare(20000)
        listOf(julie, tom, ines).forEach(viewModel::addSharePerson)
        viewModel.changeShareNotes("Week-end d'octobre")
        coEvery { transactionRepository.getTransactions(accountId) } returns pageOf(listOf(gite.copy(myShareCents = 20000)))

        viewModel.saveShare()
        advanceUntilIdle()

        assertEquals(
            MyShareRequest(
                myShareCents = 20000,
                `object` = "GITE DES CIMES",
                notes = "Week-end d'octobre",
                shares = listOf(MyShareRequestShare("c-julie"), MyShareRequestShare("c-tom"), MyShareRequestShare("c-ines")),
            ),
            request.captured,
        )
        assertTrue(share.isShared)
        assertFalse(share.isSaving)
        assertEquals("Partage enregistré", share.message)
        assertEquals(20000, viewModel.uiState.value.detail!!.transaction.myShareCents)
        assertEquals(20000, viewModel.uiState.value.transactions.single().myShareCents)
    }

    @Test
    fun `saving amounts edited by hand sends every amount`() = runTest {
        val request = slot<MyShareRequest>()
        coEvery { peerDebtRepository.setMyShare("tx-gite", capture(request)) } returns
            Result.success(sharedGite(20000, julie to 40000, tom to 20000))
        openGite()
        viewModel.setShareEnabled(true)
        viewModel.changeMyShare(20000)
        viewModel.addSharePerson(julie)
        viewModel.addSharePerson(tom)
        viewModel.editShareAmount("c-julie", 40000)
        viewModel.editShareAmount("c-tom", 20000)

        viewModel.saveShare()
        advanceUntilIdle()

        assertEquals(listOf(40000, 20000), request.captured.shares.map { it.amountCents })
        assertTrue("uneven amounts read back stay the owner's", share.editedByHand)
    }

    @Test
    fun `a refused share says the server's words and keeps the draft`() = runTest {
        coEvery { peerDebtRepository.setMyShare("tx-gite", any()) } returns
            Result.failure(RuntimeException("Contact not found: c-tom"))
        openGite()
        viewModel.setShareEnabled(true)
        viewModel.addSharePerson(tom)

        viewModel.saveShare()
        advanceUntilIdle()

        assertEquals("Contact not found: c-tom", share.error)
        assertFalse(share.isSaving)
        assertTrue(share.enabled)
        assertEquals(listOf("Tom"), share.people.map { it.name })
        assertNull(viewModel.uiState.value.detail!!.transaction.myShareCents)
    }

    @Test
    fun `switching off a shared spend takes the share back`() = runTest {
        coEvery { peerDebtRepository.removeMyShare("tx-gite") } returns
            Result.success(MyShare(transactionId = "tx-gite", amountCents = -80000, canBeShared = true))
        openGite(sharedGite(20000, tom to 60000))
        // The list read again after the removal no longer carries a part.
        coEvery { transactionRepository.getTransactions(accountId) } returns pageOf(listOf(gite))

        viewModel.setShareEnabled(false)
        advanceUntilIdle()

        coVerify(exactly = 1) { peerDebtRepository.removeMyShare("tx-gite") }
        assertFalse(share.enabled)
        assertFalse(share.isShared)
        assertEquals("Partage retiré", share.message)
        assertNull(viewModel.uiState.value.detail!!.transaction.myShareCents)
    }

    @Test
    fun `a share that cannot be taken back stays on, with the reason`() = runTest {
        coEvery { peerDebtRepository.removeMyShare("tx-gite") } returns
            Result.failure(RuntimeException("Tom already gave back 1000 cents of this"))
        openGite(sharedGite(20000, tom to 60000))

        viewModel.setShareEnabled(false)
        advanceUntilIdle()

        assertTrue(share.enabled)
        assertTrue(share.isShared)
        assertEquals("Tom already gave back 1000 cents of this", share.error)
    }

    @Test
    fun `switching off a draft only folds it, nothing is sent`() = runTest {
        openGite()
        viewModel.setShareEnabled(true)

        viewModel.setShareEnabled(false)
        advanceUntilIdle()

        assertFalse(share.enabled)
        coVerify(exactly = 0) { peerDebtRepository.removeMyShare(any()) }
    }

    @Test
    fun `the picker offers the owner's contacts and a person created there joins the share`() = runTest {
        coEvery { contactRepository.getContacts() } returns Result.success(listOf(julie, tom))
        coEvery { contactRepository.createContact("Inès") } returns Result.success(ines)
        openGite()
        viewModel.setShareEnabled(true)

        viewModel.openShareContactPicker()
        assertTrue(viewModel.uiState.value.shareContacts!!.isLoading)
        advanceUntilIdle()
        assertEquals(listOf("Julie", "Tom"), viewModel.uiState.value.shareContacts!!.contacts.map { it.name })

        viewModel.pickShareContact(julie)
        assertNull(viewModel.uiState.value.shareContacts)

        viewModel.openShareContactPicker()
        advanceUntilIdle()
        viewModel.createShareContact("Inès")
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.shareContacts)
        assertEquals(listOf("Julie", "Inès"), share.people.map { it.name })
    }

    // « Ce virement est » (MAG-452): 280 € received from Tom, to be given back.

    private val virement = Transaction(id = "tx-vir", label = "VIR TOM MARTIN", amountCents = 28000)

    private fun receivedDebt(peerDebt: PeerDebt? = null, suggested: String? = "c-tom") = TransactionDebt(
        transactionId = "tx-vir",
        amountCents = 28000,
        canBeDebt = true,
        suggestedContactId = suggested,
        peerDebt = peerDebt,
    )

    private fun recordedDebt(cents: Int) = PeerDebt(
        id = "debt-vir",
        direction = PeerDebt.DIRECTION_I_OWE,
        `object` = "Avance restaurant",
        notes = "À rendre",
        originTransactionId = "tx-vir",
        amountCents = cents,
        remainingCents = cents,
        shares = listOf(PeerDebtShare(id = "s-0", contactId = tom.id, contactName = tom.name, amountCents = cents, remainingCents = cents)),
    )

    private fun kotlinx.coroutines.test.TestScope.openVirement(debt: TransactionDebt = receivedDebt()) {
        coEvery { transactionRepository.getTransactions(accountId) } returns pageOf(listOf(virement))
        coEvery { transactionRepository.getTransfer("tx-vir") } returns Result.success(TransferInfo())
        coEvery { peerDebtRepository.getDebt("tx-vir") } returns Result.success(debt)
        coEvery { contactRepository.getContacts() } returns Result.success(listOf(julie, tom))
        viewModel = newViewModel()
        advanceUntilIdle()
        viewModel.openDetail("tx-vir")
        advanceUntilIdle()
    }

    private val debt get() = viewModel.uiState.value.detail!!.debt!!

    @Test
    fun `opening a received transfer reads its debt, a recette with the suggested person ready`() = runTest {
        openVirement()

        assertFalse(debt.enabled)
        assertTrue(debt.canBeDebt)
        assertEquals(28000, debt.totalCents)
        assertEquals(28000, debt.amountCents)
        assertEquals("c-tom", debt.contactId)
        assertEquals("Tom", debt.contactName)
        assertNull(viewModel.uiState.value.detail!!.share)
        coVerify { peerDebtRepository.getDebt("tx-vir") }
        coVerify(exactly = 0) { peerDebtRepository.getMyShare("tx-vir") }
    }

    @Test
    fun `a spend never reads a debt`() = runTest {
        openGite()

        assertNull(viewModel.uiState.value.detail!!.debt)
        coVerify(exactly = 0) { peerDebtRepository.getDebt("tx-gite") }
    }

    @Test
    fun `a transfer already given back opens on A rembourser with its person and amount`() = runTest {
        openVirement(receivedDebt(recordedDebt(15000)))

        assertTrue(debt.enabled)
        assertTrue(debt.isRecorded)
        assertEquals("Tom", debt.contactName)
        assertEquals(15000, debt.amountCents)
        assertEquals("Avance restaurant", debt.subject)
    }

    @Test
    fun `choosing A rembourser opens the draft and Une recette folds it, nothing is sent`() = runTest {
        openVirement()

        viewModel.setDebtEnabled(true)
        assertTrue(debt.enabled)
        assertTrue(debt.canSave)
        assertEquals("Crée une dette de ${formatCents(28000)} envers Tom, dans « Tu dois »", debt.summary("EUR"))

        viewModel.setDebtEnabled(false)
        advanceUntilIdle()

        assertFalse(debt.enabled)
        coVerify(exactly = 0) { peerDebtRepository.recordDebt(any(), any()) }
        coVerify(exactly = 0) { peerDebtRepository.removeDebt(any()) }
    }

    @Test
    fun `without a suggestion the person is left to choose and saving waits for them`() = runTest {
        openVirement(receivedDebt(suggested = null))
        viewModel.setDebtEnabled(true)

        assertNull(debt.contactId)
        assertFalse(debt.canSave)
        assertNotNull(debt.problem)

        viewModel.openDebtContactPicker()
        assertTrue(viewModel.uiState.value.debtContacts!!.isLoading)
        advanceUntilIdle()
        assertEquals(listOf("Julie", "Tom"), viewModel.uiState.value.debtContacts!!.contacts.map { it.name })

        viewModel.pickDebtContact(julie)

        assertNull(viewModel.uiState.value.debtContacts)
        assertEquals("c-julie", debt.contactId)
        assertTrue(debt.canSave)
    }

    @Test
    fun `a person created in the picker becomes the person owed`() = runTest {
        coEvery { contactRepository.createContact("Inès") } returns Result.success(ines)
        openVirement(receivedDebt(suggested = null))
        viewModel.setDebtEnabled(true)

        viewModel.openDebtContactPicker()
        advanceUntilIdle()
        viewModel.createDebtContact("Inès")
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.debtContacts)
        assertEquals("c-ines", debt.contactId)
    }

    @Test
    fun `the amount to give back is kept above zero and within the transfer`() = runTest {
        openVirement()
        viewModel.setDebtEnabled(true)

        viewModel.changeDebtAmount(10000)
        assertEquals(10000, debt.amountCents)
        assertTrue(debt.canSave)

        viewModel.changeDebtAmount(99999)
        assertEquals(28000, debt.amountCents)

        viewModel.changeDebtAmount(-500)
        assertEquals(0, debt.amountCents)
        assertFalse(debt.canSave)
        assertNotNull(debt.problem)
    }

    @Test
    fun `saving records the debt with its person, amount, object and notes`() = runTest {
        val saved = slot<TransactionDebtRequest>()
        coEvery { peerDebtRepository.recordDebt("tx-vir", capture(saved)) } returns Result.success(receivedDebt(recordedDebt(15000)))
        openVirement()
        viewModel.setDebtEnabled(true)
        viewModel.changeDebtAmount(15000)
        viewModel.changeDebtSubject("  Avance restaurant ")
        viewModel.changeDebtNotes("À rendre")

        viewModel.saveDebt()
        assertTrue(debt.isSaving)
        advanceUntilIdle()

        assertEquals(TransactionDebtRequest("c-tom", 15000, "Avance restaurant", "À rendre"), saved.captured)
        assertFalse(debt.isSaving)
        assertTrue(debt.isRecorded)
        assertTrue(debt.enabled)
        assertEquals("Dette enregistrée", debt.message)
        assertNull(debt.error)
    }

    @Test
    fun `a refused debt says the server's words and keeps the draft editable`() = runTest {
        coEvery { peerDebtRepository.recordDebt("tx-vir", any()) } returns Result.failure(RuntimeException("Le montant dépasse le virement"))
        openVirement()
        viewModel.setDebtEnabled(true)
        viewModel.changeDebtAmount(15000)

        viewModel.saveDebt()
        advanceUntilIdle()

        assertEquals("Le montant dépasse le virement", debt.error)
        assertFalse(debt.isSaving)
        assertFalse(debt.isRecorded)
        assertTrue(debt.enabled)
        assertEquals(15000, debt.amountCents)
        assertTrue(debt.canSave)
    }

    @Test
    fun `going back to Une recette takes the recorded debt back`() = runTest {
        coEvery { peerDebtRepository.removeDebt("tx-vir") } returns Result.success(receivedDebt())
        openVirement(receivedDebt(recordedDebt(15000)))

        viewModel.setDebtEnabled(false)
        advanceUntilIdle()

        coVerify { peerDebtRepository.removeDebt("tx-vir") }
        assertFalse(debt.enabled)
        assertFalse(debt.isRecorded)
        assertEquals("Dette retirée", debt.message)
        assertEquals("Tom", debt.contactName)
    }

    @Test
    fun `a debt that cannot be taken back stays on, with the reason`() = runTest {
        coEvery { peerDebtRepository.removeDebt("tx-vir") } returns Result.failure(RuntimeException("Tom a déjà remboursé 100 €"))
        openVirement(receivedDebt(recordedDebt(15000)))

        viewModel.setDebtEnabled(false)
        advanceUntilIdle()

        assertTrue(debt.enabled)
        assertTrue(debt.isRecorded)
        assertEquals("Tom a déjà remboursé 100 €", debt.error)
        assertFalse(debt.isSaving)
    }

    // « Ce virement est » (MAG-453): 400 € received from Tom, who owes 200 € on the gîte and 50 € on a ticket.

    private val tomTransfer = Transaction(id = "tx-tom", label = "VIR. DE TOM MARTIN", amountCents = 40000)

    private val giteShare = PeerAllocationCandidate(
        shareId = "share-gite", peerDebtId = "debt-gite", `object` = "Gîte de Crozon", contactId = "c-tom", contactName = "Tom",
        amountCents = 20000, remainingCents = 20000, availableCents = 20000, fromContact = true,
    )
    private val ticketShare = PeerAllocationCandidate(
        shareId = "share-ticket", peerDebtId = "debt-ticket", `object` = "Ciné", contactId = "c-julie", contactName = "Julie",
        amountCents = 5000, remainingCents = 5000, availableCents = 5000,
    )

    private fun tomAllocation(kind: String? = null, giteCents: Int = 0) = TransactionAllocation(
        transactionId = "tx-tom",
        amountCents = 40000,
        direction = PeerKind.INCOMING,
        canBeAllocated = true,
        peerKind = kind,
        contact = ContactRef("c-tom", "Tom"),
        allocatedCents = giteCents,
        unallocatedCents = 40000 - giteCents,
        allocations = if (giteCents > 0) listOf(PeerAllocationLine("share-gite", "debt-gite", giteCents)) else emptyList(),
        candidates = listOf(giteShare.copy(allocatedCents = giteCents), ticketShare),
    )

    private fun kotlinx.coroutines.test.TestScope.openTomTransfer(allocation: TransactionAllocation = tomAllocation()) {
        coEvery { transactionRepository.getTransactions(accountId) } returns pageOf(listOf(tomTransfer))
        coEvery { transactionRepository.getTransfer("tx-tom") } returns Result.success(TransferInfo())
        coEvery { peerDebtRepository.getDebt("tx-tom") } returns Result.success(TransactionDebt("tx-tom", 40000, canBeDebt = true))
        coEvery { peerDebtRepository.getAllocation("tx-tom") } returns Result.success(allocation)
        coEvery { contactRepository.getContacts() } returns Result.success(listOf(julie, tom))
        viewModel = newViewModel()
        advanceUntilIdle()
        viewModel.openDetail("tx-tom")
        advanceUntilIdle()
    }

    private val peer get() = viewModel.uiState.value.detail!!.peer!!

    @Test
    fun `a transfer from a contact asks what it is, the contact's créances first and nothing checked`() = runTest {
        openTomTransfer()

        assertTrue(peer.isShown)
        assertNull(peer.kind)
        assertFalse(peer.canSave)
        assertEquals(listOf("share-gite", "share-ticket"), peer.choices.map { it.candidate.shareId })
        assertTrue(peer.choices.none { it.selected })
        assertEquals(listOf(PeerKind.INCOME, PeerKind.REPAYMENT, PeerKind.NEW_DEBT), PeerKind.choicesFor(peer.direction))
    }

    @Test
    fun `a transfer from nobody in particular is not asked`() = runTest {
        openTomTransfer(tomAllocation().copy(contact = null))

        assertFalse(peer.isShown)
    }

    @Test
    fun `choosing Un remboursement waits for a créance to be checked`() = runTest {
        openTomTransfer()

        viewModel.choosePeerKind(PeerKind.REPAYMENT)

        assertEquals(PeerKind.REPAYMENT, peer.kind)
        assertFalse(peer.canSave)
        assertEquals("Coche au moins une créance qu'il couvre.", peer.problem)
        assertEquals(40000, peer.unallocatedCents)
    }

    @Test
    fun `checking the gîte covers Tom's 200 €, the other 200 € stay Non affecté`() = runTest {
        openTomTransfer()
        viewModel.choosePeerKind(PeerKind.REPAYMENT)

        viewModel.togglePeerShare("share-gite")

        assertEquals(20000, peer.choices.first { it.candidate.shareId == "share-gite" }.amountCents)
        assertEquals(20000, peer.unallocatedCents)
        assertTrue(peer.canSave)

        viewModel.togglePeerShare("share-gite")

        assertFalse(peer.choices.first().selected)
        assertEquals(40000, peer.unallocatedCents)
    }

    @Test
    fun `the amount on a créance stays within what it waits for`() = runTest {
        openTomTransfer()
        viewModel.choosePeerKind(PeerKind.REPAYMENT)
        viewModel.togglePeerShare("share-gite")

        viewModel.changePeerShareAmount("share-gite", 15000)
        assertEquals(25000, peer.unallocatedCents)

        viewModel.changePeerShareAmount("share-gite", 99999)
        assertEquals(20000, peer.allocatedCents)

        viewModel.changePeerShareAmount("share-gite", 0)
        assertFalse(peer.canSave)
        assertEquals("Chaque montant doit être positif.", peer.problem)
    }

    @Test
    fun `saving a repayment sends the créances covered and reads the answer back`() = runTest {
        val sent = slot<PeerAllocationRequest>()
        coEvery { peerDebtRepository.setAllocation("tx-tom", capture(sent)) } returns
            Result.success(tomAllocation(PeerKind.REPAYMENT, giteCents = 20000))
        openTomTransfer()
        viewModel.choosePeerKind(PeerKind.REPAYMENT)
        viewModel.togglePeerShare("share-gite")

        viewModel.savePeer()
        assertTrue(peer.isSaving)
        advanceUntilIdle()

        assertEquals(PeerAllocationRequest(PeerKind.REPAYMENT, allocations = listOf(PeerAllocationRequestLine("share-gite", 20000))), sent.captured)
        assertFalse(peer.isSaving)
        assertEquals(PeerKind.REPAYMENT, peer.savedKind)
        assertTrue(peer.choices.first().selected)
        assertEquals(20000, peer.unallocatedCents)
        assertEquals("Remboursement enregistré", peer.message)
        // The list is read again: the month's income and the pill follow the answer.
        coVerify(atLeast = 2) { transactionRepository.getTransactions(accountId) }
    }

    @Test
    fun `A rembourser proposes the transfer's contact and sends a dette through the allocation`() = runTest {
        val sent = slot<PeerAllocationRequest>()
        coEvery { peerDebtRepository.setAllocation("tx-tom", capture(sent)) } returns Result.success(tomAllocation(PeerKind.NEW_DEBT))
        openTomTransfer()

        viewModel.choosePeerKind(PeerKind.NEW_DEBT)
        assertEquals("Tom", peer.personName)
        viewModel.changePeerDebtAmount(28000)
        viewModel.changePeerSubject(" Avance ")
        viewModel.savePeer()
        advanceUntilIdle()

        assertEquals(PeerAllocationRequest(PeerKind.NEW_DEBT, debt = PeerAllocationDebt("c-tom", 28000, "Avance")), sent.captured)
        assertEquals("Dette enregistrée", peer.message)
        coVerify(exactly = 0) { peerDebtRepository.recordDebt(any(), any()) }
    }

    @Test
    fun `the person of a dette is picked in the full-screen picker`() = runTest {
        openTomTransfer()
        viewModel.choosePeerKind(PeerKind.NEW_DEBT)

        viewModel.openPeerContactPicker()
        assertTrue(viewModel.uiState.value.peerContacts!!.isLoading)
        advanceUntilIdle()
        viewModel.pickPeerContact(julie)

        assertNull(viewModel.uiState.value.peerContacts)
        assertEquals("c-julie", peer.personId)
    }

    @Test
    fun `a refused answer says the server's words and keeps the draft`() = runTest {
        coEvery { peerDebtRepository.setAllocation("tx-tom", any()) } returns Result.failure(RuntimeException("Trop sur cette créance"))
        openTomTransfer()
        viewModel.choosePeerKind(PeerKind.REPAYMENT)
        viewModel.togglePeerShare("share-gite")

        viewModel.savePeer()
        advanceUntilIdle()

        assertEquals("Trop sur cette créance", peer.error)
        assertFalse(peer.isSaving)
        assertNull(peer.savedKind)
        assertTrue(peer.choices.first().selected)
        assertTrue(peer.canSave)
    }

    @Test
    fun `a transfer already allocated opens with its créance checked, and Une recette held has nothing new to send`() = runTest {
        openTomTransfer(tomAllocation(PeerKind.REPAYMENT, giteCents = 20000))

        assertEquals(PeerKind.REPAYMENT, peer.kind)
        assertEquals(20000, peer.choices.first().amountCents)
        assertEquals(20000, peer.unallocatedCents)

        viewModel.choosePeerKind(PeerKind.INCOME)
        assertTrue(peer.canSave)
    }

    @Test
    fun `money sent offers to repay a dette, a new créance or something else`() = runTest {
        val sent = slot<PeerAllocationRequest>()
        coEvery { peerDebtRepository.setAllocation("tx-tom", capture(sent)) } answers {
            Result.success(tomAllocation(PeerKind.NEW_CLAIM).copy(direction = PeerKind.OUTGOING, amountCents = -40000))
        }
        openTomTransfer(tomAllocation().copy(direction = PeerKind.OUTGOING, amountCents = -40000, candidates = emptyList()))

        assertEquals(listOf(PeerKind.REPAYMENT, PeerKind.NEW_CLAIM, PeerKind.OTHER), PeerKind.choicesFor(peer.direction))
        viewModel.choosePeerKind(PeerKind.NEW_CLAIM)
        assertEquals("Crée une créance de ${formatCents(40000)} sur Tom, dans « On te doit »", peer.summary())
        viewModel.savePeer()
        advanceUntilIdle()

        assertEquals(PeerKind.NEW_CLAIM, sent.captured.kind)
        assertEquals("c-tom", sent.captured.claim?.contactId)
        assertEquals("Créance enregistrée", peer.message)
    }
}

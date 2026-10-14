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
import com.maggie.app.data.model.Envelope
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
import com.maggie.app.data.repository.RecurringOperationRepository
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
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TransactionViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var transactionRepository: TransactionRepository
    private lateinit var categoryRepository: CategoryRepository
    private lateinit var mercureService: MercureService
    private lateinit var authRepository: AuthRepository
    private lateinit var recurringRepository: RecurringOperationRepository
    private lateinit var ruleRepository: CategorizationRuleRepository
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
        coEvery { recurringRepository.getRecords() } returns Result.success(emptyList())
        coEvery { authRepository.getUserId() } returns "user-1"
        every { mercureService.subscribe(any()) } returns emptyFlow()
        coEvery { categoryRepository.getCategories() } returns Result.success(sampleCategories)
        coEvery { transactionRepository.getTransactions(accountId) } returns Result.success(sampleTransactions)
        coEvery { transactionRepository.getIncidents(accountId) } returns Result.success(emptyList())
    }

    private fun newViewModel() =
        TransactionViewModel(
            transactionRepository, categoryRepository, accountId, mercureService, authRepository,
            recurringRepository, ruleRepository,
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

        coEvery { transactionRepository.getTransactions(accountId) } returns Result.success(
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
        coEvery { transactionRepository.getTransactions(accountId) } returns Result.success(listOf(marked, sampleTransactions[1]))
        coEvery { transactionRepository.getTransfer("tx-1") } returns Result.success(pairedInfo)
        coEvery { transactionRepository.setTransfer("tx-1", false, null) } returns
            Result.success(TransferInfo(transferKind = "none", transferSource = "manual"))
        viewModel = newViewModel()
        advanceUntilIdle()
        viewModel.openDetail("tx-1")
        advanceUntilIdle()
        coEvery { transactionRepository.getTransactions(accountId) } returns Result.success(
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
            Result.success(listOf(rejectedDebit, rejectedCredit))
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
            Result.success(listOf(rejectedDebit, rejectedCredit))
        coEvery { transactionRepository.getTransfer("tx-rej") } returns Result.success(rejectionInfo)
        coEvery { transactionRepository.setTransfer("tx-rej", false, null) } returns
            Result.success(TransferInfo(transferKind = "none", transferSource = "manual"))
        viewModel = newViewModel()
        advanceUntilIdle()
        viewModel.openDetail("tx-rej")
        advanceUntilIdle()
        coEvery { transactionRepository.getTransactions(accountId) } returns Result.success(
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
        coEvery { transactionRepository.getTransactions(accountId) } returns Result.success(
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

    @Test
    fun `filing the open line puts its category on the detail and refreshes the list`() = runTest {
        coEvery { transactionRepository.getTransfer("tx-1") } returns Result.success(TransferInfo())
        val filed = sampleTransactions[0].copy(category = "/api/categories/cat-2")
        var served = sampleTransactions
        coEvery { transactionRepository.getTransactions(accountId) } answers { Result.success(served) }
        coEvery { transactionRepository.setCategory("tx-1", "cat-2") } answers {
            served = listOf(filed, sampleTransactions[1])
            Result.success(filed)
        }
        viewModel = newViewModel()
        advanceUntilIdle()
        viewModel.openDetail("tx-1")
        advanceUntilIdle()

        viewModel.setCategory("cat-2")
        advanceUntilIdle()

        val detail = viewModel.uiState.value.detail!!
        assertEquals("cat-2", detail.transaction.categoryId)
        assertFalse(detail.isSaving)
        assertNull(detail.error)
        coVerify(atLeast = 2) { transactionRepository.getTransactions(accountId) }
    }

    @Test
    fun `a refused filing says why on the detail and keeps the category`() = runTest {
        coEvery { transactionRepository.getTransfer("tx-1") } returns Result.success(TransferInfo())
        coEvery { transactionRepository.setCategory("tx-1", "cat-2") } returns Result.failure(RuntimeException("422"))
        viewModel = newViewModel()
        advanceUntilIdle()
        viewModel.openDetail("tx-1")
        advanceUntilIdle()

        viewModel.setCategory("cat-2")
        advanceUntilIdle()

        val detail = viewModel.uiState.value.detail!!
        assertEquals("422", detail.error)
        assertNull(detail.transaction.categoryId)
        assertFalse(detail.isSaving)
    }

    @Test
    fun `filing with no line open does nothing`() = runTest {
        viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.setCategory("cat-2")
        advanceUntilIdle()

        coVerify(exactly = 0) { transactionRepository.setCategory(any(), any()) }
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
        coEvery { transactionRepository.getTransactions(accountId) } returns Result.success(
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
        coEvery { transactionRepository.getTransactions(accountId) } answers { Result.success(served) }
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
        coEvery { transactionRepository.getTransactions(accountId) } returns Result.success(listOf(attached, sampleTransactions[1]))
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
        coEvery { transactionRepository.getTransactions(accountId) } returns Result.success(
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
        coEvery { transactionRepository.getTransactions(accountId) } returns Result.success(listOf(internal, sampleTransactions[1]))
        coEvery { transactionRepository.setTransfer("tx-1", false, null) } returns
            Result.success(TransferInfo(transferKind = "none", transferSource = "manual"))
        openSheet()
        assertEquals(2, visibleSheetSections(viewModel.uiState.value.detail!!.transaction.transferKind).size)
        coEvery { transactionRepository.getTransactions(accountId) } returns Result.success(sampleTransactions)

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
        coEvery { transactionRepository.getTransactions(accountId) } returns Result.success(listOf(sampleTransactions[0], salary))
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
            requests += firstArg<RulePreviewRequest>().labelPattern
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
        advanceUntilIdle()

        viewModel.saveRule()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.ruleDraft)
        assertEquals("Supermarché", created.captured.labelPattern)
        assertEquals("/api/categories/cat-1", created.captured.category)
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
        coEvery { transactionRepository.getTransactions(accountId) } returns Result.success(listOf(attributed, sampleTransactions[1]))
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
        coEvery { transactionRepository.getTransactions(accountId) } answers { Result.success(served) }
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
        coEvery { transactionRepository.getTransactions(accountId) } answers { Result.success(served) }
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
}

package com.maggie.app.ui.screens.finance

import com.maggie.app.data.api.TransactionCreateRequest
import com.maggie.app.data.model.Category
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureEvent
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.mercure.MercureTopics
import com.maggie.app.data.model.Transaction
import com.maggie.app.data.model.TransferInfo
import com.maggie.app.data.model.TransferLeg
import com.maggie.app.data.repository.CategoryRepository
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
        coEvery { authRepository.getUserId() } returns "user-1"
        every { mercureService.subscribe(any()) } returns emptyFlow()
        coEvery { categoryRepository.getCategories() } returns Result.success(sampleCategories)
        coEvery { transactionRepository.getTransactions(accountId) } returns Result.success(sampleTransactions)
    }

    private fun newViewModel() =
        TransactionViewModel(transactionRepository, categoryRepository, accountId, mercureService, authRepository)

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
}

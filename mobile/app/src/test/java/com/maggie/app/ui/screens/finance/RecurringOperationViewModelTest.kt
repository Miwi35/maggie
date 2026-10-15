package com.maggie.app.ui.screens.finance

import com.maggie.app.data.api.RecurringOperationWriteRequest
import com.maggie.app.data.model.AcceptRecurringSuggestionsResult
import com.maggie.app.data.model.Account
import com.maggie.app.data.model.Category
import com.maggie.app.data.model.MissedOccurrence
import com.maggie.app.data.model.ProposalClaim
import com.maggie.app.data.model.RecurringAttachReport
import com.maggie.app.data.model.RecurringOperationPreview
import com.maggie.app.data.model.RecurringOperationPreviewMatch
import com.maggie.app.data.model.RecurringOperationPreviewRequest
import com.maggie.app.data.model.RecurringOperationRecord
import com.maggie.app.data.model.RecurringOperationsOverview
import com.maggie.app.data.model.RecurringProposal
import com.maggie.app.data.model.RecurringSeries
import com.maggie.app.data.model.RecurringSuggestion
import com.maggie.app.data.model.Transaction
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureEvent
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.mercure.MercureTopics
import com.maggie.app.data.repository.AccountRepository
import com.maggie.app.data.repository.CategoryRepository
import com.maggie.app.data.repository.RecurringOperationRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecurringOperationViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var repository: RecurringOperationRepository
    private lateinit var categoryRepository: CategoryRepository
    private lateinit var accountRepository: AccountRepository
    private lateinit var mercureService: MercureService
    private lateinit var authRepository: AuthRepository

    private val rent = RecurringSeries(
        id = "rent",
        label = "Loyer",
        categoryId = "cat-rent",
        accountId = "acc-1",
        referenceAmountCents = -90000,
        monthlyCostCents = -90000,
        yearlyCostCents = -1080000,
        nextOccurrenceOn = "2026-05-05",
        missedOccurrence = MissedOccurrence(occurrenceOn = "2026-04-05", daysOverdue = 17),
    )

    private val lateRent = RecurringProposal(
        transactionId = "tx-april",
        label = "LOYER SCI VERDA",
        bookedAt = "2026-04-12",
        amountCents = -90000,
        reason = "late",
        recurringOperationId = "rent",
        recurringOperationLabel = "Loyer",
        occurrenceOn = "2026-04-05",
        dateGapDays = 7,
        referenceAmountCents = -90000,
    )

    private val overview = RecurringOperationsOverview(operations = listOf(rent), proposals = listOf(lateRent))

    private val record = RecurringOperationRecord(
        id = "rent",
        label = "Loyer",
        counterpartyName = "LOYER SCI VERDA",
        category = "/api/categories/cat-rent",
        account = "/api/accounts/acc-1",
        anchorOn = "2026-03-05",
        referenceAmountCents = -90000,
    )

    private val detected = RecurringSuggestion(
        label = "Netflix",
        counterpartyName = "NETFLIX",
        counterpartyKey = "netflix",
        accountId = "acc-1",
        period = "monthly",
        anchorOn = "2026-02-14",
        dayRule = "fixed_day",
        referenceAmountCents = -1349,
        occurrences = 8,
    )

    private val emptyReport = RecurringAttachReport(dryRun = true)

    private fun request() = RecurringOperationWriteRequest(
        label = "Loyer",
        counterpartyName = "LOYER SCI VERDA",
        labelPattern = null,
        account = "/api/accounts/acc-1",
        category = "/api/categories/cat-rent",
        period = "monthly",
        dayRule = "fixed_day",
        anchorOn = "2026-03-05",
        referenceAmountCents = -90000,
        referenceSource = "declared",
        amountTolerancePercent = 20,
        dateToleranceDays = 5,
        endsOn = null,
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        repository = mockk()
        categoryRepository = mockk()
        accountRepository = mockk()
        coEvery { categoryRepository.getCategories() } returns Result.success(listOf(Category(id = "cat-rent", name = "Logement")))
        coEvery { accountRepository.getAccounts() } returns Result.success(listOf(Account(id = "acc-1", name = "Courant")))
        coEvery { repository.getOverview() } returns Result.success(overview)
        coEvery { repository.getRecords() } returns Result.success(listOf(record))
        coEvery { repository.getSuggestions() } returns Result.success(emptyList())
        coEvery { repository.getSeriesTransactions(any()) } returns Result.success(emptyList())
        mercureService = mockk()
        authRepository = mockk()
        coEvery { authRepository.getUserId() } returns "user-1"
        every { mercureService.subscribe(any()) } returns emptyFlow()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = RecurringOperationViewModel(repository, categoryRepository, accountRepository, mercureService, authRepository)

    @Test
    fun `loading shows a spinner until the overview answers`() = runTest {
        val viewModel = viewModel()

        assertNull(viewModel.uiState.value.overview)
        assertFalse(viewModel.uiState.value.isEmpty)

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals(listOf("rent"), state.operations.map { it.id })
        assertEquals(listOf("tx-april"), state.proposals.map { it.transactionId })
        assertEquals("LOYER SCI VERDA", state.records.getValue("rent").counterpartyName)
        assertEquals(listOf("Logement"), state.categories.map { it.name })
    }

    @Test
    fun `nothing at all is the empty state, not an error`() = runTest {
        coEvery { repository.getOverview() } returns Result.success(RecurringOperationsOverview())
        coEvery { repository.getRecords() } returns Result.success(emptyList())

        val viewModel = viewModel()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isEmpty)
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `a suggestion alone keeps the screen from being empty`() = runTest {
        coEvery { repository.getOverview() } returns Result.success(RecurringOperationsOverview())
        coEvery { repository.getSuggestions() } returns Result.success(listOf(detected))

        val viewModel = viewModel()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isEmpty)
    }

    @Test
    fun `an overview that cannot be read is an error, and no suggestion hides it`() = runTest {
        coEvery { repository.getOverview() } returns Result.failure(RuntimeException("503"))

        val viewModel = viewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("503", state.error)
        assertNull(state.overview)
        assertFalse(state.isLoading)
    }

    @Test
    fun `suggestions that cannot be read are ignored, the series still read`() = runTest {
        coEvery { repository.getSuggestions() } returns Result.failure(RuntimeException("500"))

        val viewModel = viewModel()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.error)
        assertEquals(1, viewModel.uiState.value.operations.size)
    }

    @Test
    fun `accepting a proposal attaches the line to the due date it was read against, then reloads`() = runTest {
        coEvery { repository.attachTransaction(any(), any(), any()) } returns Result.success(Unit)
        val viewModel = viewModel()
        advanceUntilIdle()
        coEvery { repository.getOverview() } returns Result.success(RecurringOperationsOverview(operations = listOf(rent)))

        viewModel.acceptProposal(lateRent, ProposalClaim(recurringOperationId = "rent", recurringOperationLabel = "Loyer", occurrenceOn = "2026-04-05T00:00:00+00:00"))
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.attachTransaction("tx-april", "rent", "2026-04-05") }
        val state = viewModel.uiState.value
        assertTrue(state.proposals.isEmpty())
        assertEquals("Transaction rattachée à « Loyer »", state.message)
        assertNull(state.actingOn)
    }

    @Test
    fun `a failed attachment keeps the proposal and says why`() = runTest {
        coEvery { repository.attachTransaction(any(), any(), any()) } returns Result.failure(RuntimeException("422"))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.acceptProposal(lateRent, ProposalClaim(recurringOperationId = "rent", occurrenceOn = "2026-04-05"))
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.proposals.size)
        assertEquals("422", viewModel.uiState.value.error)
        assertNull(viewModel.uiState.value.actingOn)
    }

    @Test
    fun `two taps on the same yes attach once`() = runTest {
        coEvery { repository.attachTransaction(any(), any(), any()) } returns Result.success(Unit)
        val viewModel = viewModel()
        advanceUntilIdle()
        val claim = ProposalClaim(recurringOperationId = "rent", occurrenceOn = "2026-04-05")

        viewModel.acceptProposal(lateRent, claim)
        viewModel.acceptProposal(lateRent, claim)
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.attachTransaction(any(), any(), any()) }
    }

    @Test
    fun `refusing a proposal writes nothing and hides it for this visit`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.dismissProposal("tx-april")

        assertTrue(viewModel.uiState.value.proposals.isEmpty())
        coVerify(exactly = 0) { repository.attachTransaction(any(), any(), any()) }
    }

    @Test
    fun `a suggestion without a heading cannot be accepted until one is chosen`() = runTest {
        coEvery { repository.getSuggestions() } returns Result.success(listOf(detected))
        val viewModel = viewModel()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.canAccept(detected))

        viewModel.chooseSuggestionCategory(suggestionKey(detected), "cat-rent")

        assertTrue(viewModel.uiState.value.canAccept(detected))
    }

    @Test
    fun `accepting a suggestion sends it with its heading and reloads`() = runTest {
        coEvery { repository.getSuggestions() } returns Result.success(listOf(detected))
        val sent = mutableListOf<List<RecurringSuggestion>>()
        coEvery { repository.acceptSuggestions(capture(sent)) } returns
            Result.success(AcceptRecurringSuggestionsResult(created = 1, labels = listOf("Netflix"), attached = 8, proposed = 0))
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.chooseSuggestionCategory(suggestionKey(detected), "cat-rent")
        coEvery { repository.getSuggestions() } returns Result.success(emptyList())

        viewModel.acceptSuggestion(detected)
        advanceUntilIdle()

        val written = sent.single().single()
        assertEquals("cat-rent", written.categoryId)
        assertEquals("Logement", written.categoryName)
        assertEquals("monthly", written.period)
        assertTrue(viewModel.uiState.value.visibleSuggestions.isEmpty())
        assertEquals("1 opération récurrente créée, 8 transaction(s) rattachée(s), 0 à confirmer.", viewModel.uiState.value.message)
    }

    @Test
    fun `a suggestion with no heading is not sent`() = runTest {
        coEvery { repository.getSuggestions() } returns Result.success(listOf(detected))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.acceptSuggestion(detected)
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.acceptSuggestions(any()) }
    }

    @Test
    fun `editing opens the stored series, and an unknown one is an error`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.startEdit("rent")
        assertEquals("rent", viewModel.uiState.value.editing?.record?.id)

        viewModel.closeEditor()
        viewModel.startEdit("gone")
        assertNull(viewModel.uiState.value.editing)
        assertNotNull(viewModel.uiState.value.error)
    }

    @Test
    fun `a series created shows what the history would attach before attaching anything`() = runTest {
        coEvery { repository.create(any()) } returns Result.success(record.copy(id = "new", label = "Loyer"))
        val line = lateRent.copy(recurringOperationId = "new")
        val other = lateRent.copy(transactionId = "tx-other", recurringOperationId = "other")
        coEvery { repository.attachHistory(true) } returns Result.success(emptyReport.copy(attachments = listOf(line, other)))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.startCreate()
        viewModel.save(request())
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNull(state.editing)
        assertEquals(listOf("tx-april"), state.preview?.lines?.map { it.transactionId })
        coVerify(exactly = 0) { repository.attachHistory(false) }
    }

    @Test
    fun `confirming the preview attaches for real`() = runTest {
        coEvery { repository.create(any()) } returns Result.success(record.copy(id = "new"))
        coEvery { repository.attachHistory(true) } returns
            Result.success(emptyReport.copy(attachments = listOf(lateRent.copy(recurringOperationId = "new"))))
        coEvery { repository.attachHistory(false) } returns Result.success(emptyReport.copy(dryRun = false, attached = 1))
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.startCreate()
        viewModel.save(request())
        advanceUntilIdle()

        viewModel.confirmPreview()
        advanceUntilIdle()

        coVerifyOrder {
            repository.create(any())
            repository.attachHistory(true)
            repository.attachHistory(false)
        }
        assertNull(viewModel.uiState.value.preview)
        assertEquals("Opération récurrente créée, 1 transaction(s) rattachée(s)", viewModel.uiState.value.message)
    }

    @Test
    fun `a series with nothing to catch up on is simply created`() = runTest {
        coEvery { repository.create(any()) } returns Result.success(record.copy(id = "new"))
        coEvery { repository.attachHistory(true) } returns Result.success(emptyReport)
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.startCreate()
        viewModel.save(request())
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.preview)
        assertNull(viewModel.uiState.value.editing)
        assertEquals("Opération récurrente créée", viewModel.uiState.value.message)
    }

    @Test
    fun `a series made from a transaction goes back to that transaction when it is done`() = runTest {
        coEvery { repository.create(any()) } returns Result.success(record.copy(id = "new"))
        coEvery { repository.attachHistory(true) } returns Result.success(emptyReport)
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.startFromTransaction(Transaction(id = "tx-april", label = "LOYER SCI VERDA", amountCents = -90000))
        viewModel.save(request())
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.returnToTransaction)
        viewModel.consumeReturnToTransaction()
        assertFalse(viewModel.uiState.value.returnToTransaction)
    }

    @Test
    fun `a transaction without a payee becomes a series recognised by its label pattern`() = runTest {
        val sent = slot<RecurringOperationWriteRequest>()
        coEvery { repository.create(capture(sent)) } returns Result.success(record.copy(id = "new"))
        coEvery { repository.attachHistory(true) } returns Result.success(emptyReport)
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.startFromTransaction(Transaction(id = "tx-jardis", label = "Sas Jardis", amountCents = -5000))
        viewModel.save(request().copy(counterpartyName = null, labelPattern = "Sas Jardis"))
        advanceUntilIdle()

        assertEquals("Sas Jardis", sent.captured.labelPattern)
        assertNull(sent.captured.counterpartyName)
        assertNull(viewModel.uiState.value.editing)
        assertEquals("Opération récurrente créée", viewModel.uiState.value.message)
    }

    @Test
    fun `a refusal in French is shown as it comes and keeps the form open`() = runTest {
        val refusal = "Indique une contrepartie ou un motif de libellé pour reconnaître les transactions"
        coEvery { repository.create(any()) } returns Result.failure(RuntimeException(refusal))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.startFromTransaction(Transaction(id = "tx-jardis", label = "Sas Jardis", amountCents = -5000))
        viewModel.save(request().copy(counterpartyName = null, labelPattern = null))
        advanceUntilIdle()

        assertNotNull(viewModel.uiState.value.editing)
        assertEquals(refusal, viewModel.uiState.value.error)
    }

    @Test
    fun `a refused save keeps the form open with the error`() = runTest {
        coEvery { repository.create(any()) } returns Result.failure(RuntimeException("422"))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.startCreate()
        viewModel.save(request())
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNotNull(state.editing)
        assertEquals("422", state.error)
        assertFalse(state.isSaving)
    }

    @Test
    fun `updating writes the stored series and reloads`() = runTest {
        coEvery { repository.update("rent", any()) } returns Result.success(record)
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.startEdit("rent")
        viewModel.save(request())
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.update("rent", any()) }
        assertNull(viewModel.uiState.value.editing)
        assertEquals("Opération récurrente enregistrée", viewModel.uiState.value.message)
    }

    @Test
    fun `deleting a series closes the editor and reloads`() = runTest {
        coEvery { repository.delete("rent") } returns Result.success(Unit)
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.startEdit("rent")

        viewModel.delete("rent")
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.editing)
        assertEquals("Opération récurrente supprimée", viewModel.uiState.value.message)
    }

    private val april = Transaction(
        id = "tx-april", label = "LOYER SCI VERDA", amountCents = -90000, bookedAt = "2026-04-12",
        recurringOperation = "/api/recurring_operations/rent", recurringOccurrenceOn = "2026-04-05", recurringSource = "auto",
    )
    private val march = april.copy(id = "tx-march", bookedAt = "2026-03-05", recurringOccurrenceOn = "2026-03-05", recurringSource = "manual")

    @Test
    fun `opening a series reads its lines, newest first`() = runTest {
        coEvery { repository.getSeriesTransactions("rent") } returns Result.success(listOf(april, march))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.startEdit("rent")
        assertNull(viewModel.uiState.value.seriesLines)
        advanceUntilIdle()

        assertEquals(listOf("tx-april", "tx-march"), viewModel.uiState.value.seriesLines?.map { it.id })
        assertNull(viewModel.uiState.value.seriesLinesError)
    }

    @Test
    fun `a series with no line reads as an empty list, not as loading`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.startEdit("rent")
        advanceUntilIdle()

        assertEquals(emptyList<Transaction>(), viewModel.uiState.value.seriesLines)
    }

    @Test
    fun `lines that cannot be read are an error of the section, and reloading recovers`() = runTest {
        coEvery { repository.getSeriesTransactions("rent") } returns Result.failure(RuntimeException("503"))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.startEdit("rent")
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.seriesLines)
        assertEquals("503", viewModel.uiState.value.seriesLinesError)
        assertNull(viewModel.uiState.value.error)

        coEvery { repository.getSeriesTransactions("rent") } returns Result.success(listOf(april))
        viewModel.reloadSeriesLines()
        advanceUntilIdle()

        assertEquals(listOf("tx-april"), viewModel.uiState.value.seriesLines?.map { it.id })
        assertNull(viewModel.uiState.value.seriesLinesError)
    }

    @Test
    fun `detaching a line takes it off the list and reloads the overview`() = runTest {
        coEvery { repository.getSeriesTransactions("rent") } returns Result.success(listOf(april, march))
        coEvery { repository.detachTransaction("tx-april") } returns Result.success(Unit)
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.startEdit("rent")
        advanceUntilIdle()

        viewModel.detachSeriesLine("tx-april")
        assertEquals("tx-april", viewModel.uiState.value.detachingId)
        advanceUntilIdle()

        assertEquals(listOf("tx-march"), viewModel.uiState.value.seriesLines?.map { it.id })
        assertNull(viewModel.uiState.value.detachingId)
        coVerify(exactly = 2) { repository.getOverview() }
    }

    @Test
    fun `a detachment that fails keeps the line and says so`() = runTest {
        coEvery { repository.getSeriesTransactions("rent") } returns Result.success(listOf(april))
        coEvery { repository.detachTransaction("tx-april") } returns Result.failure(RuntimeException("500"))
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.startEdit("rent")
        advanceUntilIdle()

        viewModel.detachSeriesLine("tx-april")
        advanceUntilIdle()

        assertEquals(listOf("tx-april"), viewModel.uiState.value.seriesLines?.map { it.id })
        assertEquals("500", viewModel.uiState.value.seriesLinesError)
        assertNull(viewModel.uiState.value.detachingId)
    }

    @Test
    fun `a Mercure update on transactions reloads the lines of the open series`() = runTest {
        val events = MutableSharedFlow<MercureEvent>()
        every { mercureService.subscribe(MercureTopics.userScoped("user-1", MercureTopics.TRANSACTIONS)) } returns events
        coEvery { repository.getSeriesTransactions("rent") } returns Result.success(listOf(march))
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.startEdit("rent")
        advanceUntilIdle()

        coEvery { repository.getSeriesTransactions("rent") } returns Result.success(listOf(april, march))
        events.emit(MercureEvent(data = "{}"))
        advanceUntilIdle()

        assertEquals(listOf("tx-april", "tx-march"), viewModel.uiState.value.seriesLines?.map { it.id })
    }

    @Test
    fun `a Mercure update with no series open reads nothing`() = runTest {
        val events = MutableSharedFlow<MercureEvent>()
        every { mercureService.subscribe(MercureTopics.userScoped("user-1", MercureTopics.TRANSACTIONS)) } returns events
        val viewModel = viewModel()
        advanceUntilIdle()

        events.emit(MercureEvent(data = "{}"))
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.getSeriesTransactions(any()) }
        assertNull(viewModel.uiState.value.seriesLines)
    }

    @Test
    fun `lines that arrive after the editor closed are dropped`() = runTest {
        coEvery { repository.getSeriesTransactions("rent") } returns Result.success(listOf(april))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.startEdit("rent")
        viewModel.closeEditor()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.seriesLines)
    }

    private val salaryAsked = RecurringOperationPreviewRequest(referenceAmountCents = 475000, amountTolerancePercent = 10)

    private val salaryFound = RecurringOperationPreview(
        total = 1,
        matches = listOf(RecurringOperationPreviewMatch("tx-salary", "VIR SEPA XYZ", 465000, "2026-10-01", "acc-1")),
    )

    @Test
    fun `an incomplete form asks for no preview and clears the previous one`() = runTest {
        coEvery { repository.previewSeries(any()) } returns Result.success(salaryFound)
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.startCreate()
        viewModel.previewRecognition(salaryAsked)
        advanceUntilIdle()
        assertEquals(salaryFound, viewModel.uiState.value.recognition.result)

        viewModel.previewRecognition(null)
        advanceUntilIdle()

        assertEquals(SeriesRecognitionState(), viewModel.uiState.value.recognition)
        coVerify(exactly = 1) { repository.previewSeries(any()) }
    }

    @Test
    fun `a complete form loads, then shows the lines the series would recognise`() = runTest {
        val sent = slot<RecurringOperationPreviewRequest>()
        coEvery { repository.previewSeries(capture(sent)) } returns Result.success(salaryFound)
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.startCreate()

        viewModel.previewRecognition(salaryAsked)
        assertTrue(viewModel.uiState.value.recognition.isLoading)
        advanceUntilIdle()

        val recognition = viewModel.uiState.value.recognition
        assertFalse(recognition.isLoading)
        assertNull(recognition.error)
        assertEquals(1, recognition.result!!.total)
        assertEquals("VIR SEPA XYZ", recognition.result!!.matches.single().label)
        assertEquals(salaryAsked, sent.captured)
    }

    @Test
    fun `a failing preview is reported on the form and the editor stays open`() = runTest {
        coEvery { repository.previewSeries(any()) } returns Result.failure(RuntimeException("Montant invalide"))
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.startCreate()

        viewModel.previewRecognition(salaryAsked)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("Montant invalide", state.recognition.error)
        assertNull(state.recognition.result)
        assertFalse(state.recognition.isLoading)
        assertNotNull(state.editing)
        assertNull(state.error)
    }

    @Test
    fun `a newer preview request replaces the older one, whatever order they answer in`() = runTest {
        val asked = mutableListOf<Int>()
        coEvery { repository.previewSeries(any()) } answers {
            val request = firstArg<RecurringOperationPreviewRequest>()
            asked += request.referenceAmountCents
            Result.success(RecurringOperationPreview(total = request.referenceAmountCents))
        }
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.startCreate()

        viewModel.previewRecognition(salaryAsked.copy(referenceAmountCents = 1))
        viewModel.previewRecognition(salaryAsked.copy(referenceAmountCents = 2))
        advanceUntilIdle()

        assertEquals(listOf(2), asked)
        assertEquals(2, viewModel.uiState.value.recognition.result!!.total)
    }

    @Test
    fun `closing the editor drops the preview`() = runTest {
        coEvery { repository.previewSeries(any()) } returns Result.success(salaryFound)
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.startCreate()
        viewModel.previewRecognition(salaryAsked)
        advanceUntilIdle()

        viewModel.closeEditor()

        assertEquals(SeriesRecognitionState(), viewModel.uiState.value.recognition)
    }
}

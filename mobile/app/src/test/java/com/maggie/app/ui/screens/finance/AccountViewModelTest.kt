package com.maggie.app.ui.screens.finance

import com.maggie.app.data.api.AccountCreateRequest
import com.maggie.app.data.api.TransactionCreateRequest
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureEvent
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.mercure.MercureTopics
import com.maggie.app.data.model.Account
import com.maggie.app.data.model.AccountTimeline
import com.maggie.app.data.model.Category
import com.maggie.app.data.model.TimelineAccount
import com.maggie.app.data.model.TimelineDay
import com.maggie.app.data.model.TimelineLine
import com.maggie.app.data.model.Transaction
import com.maggie.app.data.repository.AccountRepository
import com.maggie.app.data.repository.CategoryRepository
import com.maggie.app.data.repository.TransactionRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
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
class AccountViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var accountRepository: AccountRepository
    private lateinit var transactionRepository: TransactionRepository
    private lateinit var categoryRepository: CategoryRepository
    private lateinit var mercureService: MercureService
    private lateinit var authRepository: AuthRepository
    private lateinit var viewModel: AccountViewModel

    private val sampleAccounts = listOf(
        Account(id = "acc-1", name = "Compte courant", type = "checking", balanceCents = 125000),
        Account(id = "acc-2", name = "Livret A", type = "savings", balanceCents = 500000, isCushion = true),
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        accountRepository = mockk()
        transactionRepository = mockk()
        categoryRepository = mockk()
        mercureService = mockk()
        authRepository = mockk()
        coEvery { accountRepository.getAccounts() } returns Result.success(sampleAccounts)
        coEvery { accountRepository.accountTimeline("acc-1", null) } returns Result.success(march("acc-1", "Compte courant", previous = "2026-02"))
        coEvery { accountRepository.accountTimeline("acc-1", "2026-02") } returns Result.success(february("acc-1", "Compte courant"))
        coEvery { accountRepository.accountTimeline("acc-2", null) } returns Result.success(march("acc-2", "Livret A", previous = null))
        coEvery { categoryRepository.getCategories() } returns Result.success(listOf(Category(id = "cat-1", name = "Courses")))
        coEvery { authRepository.getUserId() } returns "user-1"
        every { mercureService.subscribe(any()) } returns emptyFlow()
    }

    private fun newViewModel() = AccountViewModel(accountRepository, transactionRepository, mercureService, authRepository, categoryRepository)

    private fun line(id: String, label: String, amount: Int = -1000) = TimelineLine(id = id, label = label, amountCents = amount)

    private fun march(accountId: String, name: String, previous: String?, isManual: Boolean = false, balance: Int = 125000) = AccountTimeline(
        month = "2026-03",
        previousMonth = previous,
        account = TimelineAccount(id = accountId, name = name, balanceCents = balance, isManual = isManual),
        days = listOf(TimelineDay(date = "2026-03-10", transactions = listOf(line("$accountId-l1", "Courses")))),
    )

    private fun february(accountId: String, name: String) = AccountTimeline(
        month = "2026-02",
        previousMonth = null,
        account = TimelineAccount(id = accountId, name = name),
        days = listOf(TimelineDay(date = "2026-02-27", transactions = listOf(line("$accountId-l0", "Loyer")))),
    )

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial load populates accounts sorted by name`() = runTest {
        viewModel = newViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(2, state.accounts.size)
        assertEquals("Compte courant", state.accounts[0].name)
        assertEquals("Livret A", state.accounts[1].name)
        assertFalse(state.isLoading)
        assertNull(state.error)
    }

    @Test
    fun `createAccount dispatches request and refreshes`() = runTest {
        coEvery { accountRepository.createAccount(any()) } returns Result.success(
            Account(id = "acc-3", name = "Espèces", type = "cash"),
        )
        viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.createAccount(AccountCreateRequest(name = "Espèces", type = "cash"))
        advanceUntilIdle()

        coVerify { accountRepository.createAccount(any()) }
        coVerify(atLeast = 2) { accountRepository.getAccounts() }
    }

    @Test
    fun `deleteAccount removes it from state`() = runTest {
        coEvery { accountRepository.deleteAccount("acc-1") } returns Result.success(Unit)
        viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.deleteAccount("acc-1")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(1, state.accounts.size)
        assertEquals("acc-2", state.accounts[0].id)
    }

    @Test
    fun `load failure sets error`() = runTest {
        coEvery { accountRepository.getAccounts() } returns Result.failure(RuntimeException("boom"))
        viewModel = newViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("boom", state.error)
        assertFalse(state.isLoading)
    }

    @Test
    fun `the first account is selected and its current month is read`() = runTest {
        viewModel = newViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("acc-1", state.selectedAccountId)
        val timeline = state.timelines.getValue("acc-1")
        assertTrue(timeline.isLoaded)
        assertFalse(timeline.isLoading)
        assertEquals(listOf("2026-03-10"), timeline.days.map { it.date })
        assertEquals("2026-02", timeline.previousMonth)
        coVerify(exactly = 0) { accountRepository.accountTimeline("acc-2", any()) }
    }

    @Test
    fun `swiping to another account reads its timeline once`() = runTest {
        viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.selectAccount("acc-2")
        advanceUntilIdle()
        viewModel.selectAccount("acc-1")
        viewModel.selectAccount("acc-2")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("acc-2", state.selectedAccountId)
        assertEquals(1, state.selectedIndex)
        assertEquals("acc-2-l1", state.timelines.getValue("acc-2").days.single().transactions.single().id)
        assertNull(state.timelines.getValue("acc-2").previousMonth)
        coVerify(exactly = 1) { accountRepository.accountTimeline("acc-2", null) }
        coVerify(exactly = 1) { accountRepository.accountTimeline("acc-1", null) }
    }

    @Test
    fun `the previous month is added after the days already read`() = runTest {
        viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.loadPreviousMonth("acc-1")
        advanceUntilIdle()

        val timeline = viewModel.uiState.value.timelines.getValue("acc-1")
        assertEquals(listOf("2026-03-10", "2026-02-27"), timeline.days.map { it.date })
        assertNull(timeline.previousMonth)
        assertFalse(timeline.isLoadingPrevious)

        viewModel.loadPreviousMonth("acc-1")
        advanceUntilIdle()
        coVerify(exactly = 1) { accountRepository.accountTimeline("acc-1", "2026-02") }
    }

    @Test
    fun `a failed first read shows an error and Réessayer reads it again`() = runTest {
        coEvery { accountRepository.accountTimeline("acc-1", null) } returns Result.failure(RuntimeException("hors ligne"))
        viewModel = newViewModel()
        advanceUntilIdle()

        val failed = viewModel.uiState.value.timelines.getValue("acc-1")
        assertEquals("hors ligne", failed.error)
        assertFalse(failed.isLoading)
        assertFalse(failed.isLoaded)

        coEvery { accountRepository.accountTimeline("acc-1", null) } returns Result.success(march("acc-1", "Compte courant", previous = null))
        viewModel.loadTimeline("acc-1")
        advanceUntilIdle()

        val retried = viewModel.uiState.value.timelines.getValue("acc-1")
        assertNull(retried.error)
        assertTrue(retried.isLoaded)
    }

    @Test
    fun `a failed older month keeps the days and raises the error`() = runTest {
        coEvery { accountRepository.accountTimeline("acc-1", "2026-02") } returns Result.failure(RuntimeException("boom"))
        viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.loadPreviousMonth("acc-1")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("boom", state.error)
        assertEquals(1, state.timelines.getValue("acc-1").pages.size)
        assertFalse(state.timelines.getValue("acc-1").isLoadingPrevious)
    }

    @Test
    fun `correcting the balance patches the account then reads its timeline again`() = runTest {
        val updated = Account(id = "acc-1", name = "Compte courant", balanceCents = 99900)
        coEvery { accountRepository.updateBalance("acc-1", 99900) } returns Result.success(updated)
        viewModel = newViewModel()
        advanceUntilIdle()
        coEvery { accountRepository.accountTimeline("acc-1", null) } returns Result.success(
            march("acc-1", "Compte courant", previous = "2026-02", isManual = true, balance = 99900),
        )

        viewModel.correctBalance("acc-1", 99900)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(99900, state.accounts.first { it.id == "acc-1" }.balanceCents)
        assertEquals(99900, state.timelines.getValue("acc-1").account?.balanceCents)
        assertTrue(state.timelines.getValue("acc-1").account?.isManual == true)
        assertNull(state.error)
        coVerify { accountRepository.updateBalance("acc-1", 99900) }
    }

    @Test
    fun `a refused balance correction keeps the balance and raises the error`() = runTest {
        coEvery { accountRepository.updateBalance("acc-1", 1) } returns Result.failure(RuntimeException("refusé"))
        viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.correctBalance("acc-1", 1)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("refusé", state.error)
        assertEquals(125000, state.accounts.first { it.id == "acc-1" }.balanceCents)
    }

    @Test
    fun `adding a transaction books it on the page's account and reads the timeline again`() = runTest {
        val request = slot<TransactionCreateRequest>()
        coEvery { transactionRepository.createTransaction(capture(request)) } returns Result.success(Transaction(id = "tx-new", label = "Café"))
        viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.addTransaction("acc-2", -350, "Café", status = "planned", categoryId = "cat-1", bookedAt = "2026-03-12")
        advanceUntilIdle()

        assertEquals("/api/accounts/acc-2", request.captured.account)
        assertEquals(-350, request.captured.amountCents)
        assertEquals("planned", request.captured.status)
        assertEquals("/api/categories/cat-1", request.captured.category)
        assertEquals("2026-03-12", request.captured.bookedAt)
        assertEquals(listOf("cat-1"), viewModel.uiState.value.categories.map { it.id })
        coVerify { accountRepository.accountTimeline("acc-2", null) }
    }

    @Test
    fun `a failed transaction creation raises the error`() = runTest {
        coEvery { transactionRepository.createTransaction(any()) } returns Result.failure(RuntimeException("invalide"))
        viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.addTransaction("acc-1", -350, "Café")
        advanceUntilIdle()

        assertEquals("invalide", viewModel.uiState.value.error)
    }

    @Test
    fun `a Mercure update on transactions reads the account on screen again and drops the others`() = runTest {
        val events = MutableSharedFlow<MercureEvent>()
        every { mercureService.subscribe(MercureTopics.userScoped("user-1", MercureTopics.TRANSACTIONS)) } returns events
        viewModel = newViewModel()
        advanceUntilIdle()
        viewModel.selectAccount("acc-2")
        advanceUntilIdle()
        viewModel.selectAccount("acc-1")
        advanceUntilIdle()
        coEvery { accountRepository.accountTimeline("acc-1", null) } returns Result.success(
            march("acc-1", "Compte courant", previous = "2026-02").copy(days = emptyList()),
        )

        events.emit(MercureEvent(data = "{}"))
        advanceUntilIdle()

        val timelines = viewModel.uiState.value.timelines
        assertTrue(timelines.getValue("acc-1").days.isEmpty())
        assertNull(timelines["acc-2"])
        assertNotNull(timelines["acc-1"])
    }

    @Test
    fun `deleting the account on screen moves to the next one`() = runTest {
        coEvery { accountRepository.deleteAccount("acc-1") } returns Result.success(Unit)
        viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.deleteAccount("acc-1")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("acc-2", state.selectedAccountId)
        assertNull(state.timelines["acc-1"])
        assertTrue(state.timelines.getValue("acc-2").isLoaded)
    }
}

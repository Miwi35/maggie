package com.maggie.app.ui.screens.finance

import com.maggie.app.data.model.BalanceBeforeSalary
import com.maggie.app.data.model.BankConnection
import com.maggie.app.data.model.CushionStatus
import com.maggie.app.data.model.Loan
import com.maggie.app.data.model.Project
import com.maggie.app.data.model.ReviewPeriod
import com.maggie.app.data.model.ReviewQueue
import com.maggie.app.data.model.ReviewState
import com.maggie.app.data.model.SalaryDelay
import com.maggie.app.data.model.SavingsAdvance
import com.maggie.app.data.model.SavingsAdvances
import com.maggie.app.data.model.TreasuryOutlook
import com.maggie.app.data.model.TreasuryOverdraft
import com.maggie.app.data.repository.BankConnectionRepository
import com.maggie.app.data.repository.CategorizationRuleRepository
import com.maggie.app.data.repository.CategoryRepository
import com.maggie.app.data.repository.CushionRepository
import com.maggie.app.data.repository.FinanceDashboardRepository
import com.maggie.app.data.repository.LoanRepository
import com.maggie.app.data.repository.ProjectRepository
import com.maggie.app.data.repository.RecurringOperationRepository
import com.maggie.app.data.repository.ReviewRepository
import com.maggie.app.data.repository.SavingsAdvancesRepository
import com.maggie.app.data.repository.TreasuryRepository
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class FinanceSummaryViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val cushion = mockk<CushionRepository>()
    private val advances = mockk<SavingsAdvancesRepository>()
    private val treasury = mockk<TreasuryRepository>()
    private val review = mockk<ReviewRepository>()
    private val rules = mockk<CategorizationRuleRepository>()
    private val recurring = mockk<RecurringOperationRepository>()
    private val loans = mockk<LoanRepository>()
    private val projects = mockk<ProjectRepository>()
    private val dashboard = mockk<FinanceDashboardRepository>()
    private val banks = mockk<BankConnectionRepository>()
    private val categories = mockk<CategoryRepository>()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        coEvery { cushion.getStatus() } returns Result.success(
            CushionStatus(isConfigured = true, targetCents = 900000, currentCents = 450000, deficitCents = 450000, state = "building"),
        )
        coEvery { advances.getSavingsAdvances() } returns Result.success(
            SavingsAdvances(
                advances = listOf(SavingsAdvance(id = "a1", amountCents = 20000, outstandingCents = 20000)),
                totalOutstandingCents = 20000,
            ),
        )
        coEvery { loans.getLoans() } returns Result.success(
            listOf(Loan(id = "l1", name = "Voiture", monthlyPaymentCents = 25000), Loan(id = "l2", name = "Travaux", monthlyPaymentCents = 10000)),
        )
        coEvery { banks.getConnections() } returns Result.success(
            listOf(
                BankConnection(id = "b1", bankName = "Banque A", status = "active", lastSyncedAt = "2026-10-14T12:00:00+00:00"),
                BankConnection(id = "b2", bankName = "Banque B", status = "active", lastSyncedAt = "2026-10-15T12:00:00+00:00"),
            ),
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun buildViewModel() = FinanceSummaryViewModel(
        cushion, advances, treasury, review, rules, recurring, loans, projects, dashboard, banks, categories,
        today = { LocalDate.of(2026, 10, 16) },
    )

    @Test
    fun `a block is loading until its figures arrive`() = runTest {
        val viewModel = buildViewModel()

        viewModel.load(listOf(SummaryBlock.CUSHION))
        assertEquals(BlockState.Loading, viewModel.blocks.value[SummaryBlock.CUSHION])

        advanceUntilIdle()
        val state = viewModel.blocks.value[SummaryBlock.CUSHION] as BlockState.Ready
        assertEquals(2, state.lines.size)
        assertEquals("Constitution en cours", state.lines[1])
    }

    @Test
    fun `a page only fetches its own blocks`() = runTest {
        val viewModel = buildViewModel()

        viewModel.load(listOf(SummaryBlock.CUSHION, SummaryBlock.SAVINGS_ADVANCES))
        advanceUntilIdle()

        assertEquals(setOf(SummaryBlock.CUSHION, SummaryBlock.SAVINGS_ADVANCES), viewModel.blocks.value.keys)
        coVerify(exactly = 0) { loans.getLoans() }
        coVerify(exactly = 0) { banks.getConnections() }
    }

    @Test
    fun `a block that fails says so and leaves the others drawn`() = runTest {
        coEvery { cushion.getStatus() } returns Result.failure(IllegalStateException("Service indisponible"))
        val viewModel = buildViewModel()

        viewModel.load(listOf(SummaryBlock.CUSHION, SummaryBlock.SAVINGS_ADVANCES))
        advanceUntilIdle()

        assertEquals(BlockState.Unavailable("Service indisponible"), viewModel.blocks.value[SummaryBlock.CUSHION])
        assertTrue(viewModel.blocks.value[SummaryBlock.SAVINGS_ADVANCES] is BlockState.Ready)
    }

    @Test
    fun `reloading keeps the figures on screen while they are fetched again`() = runTest {
        val viewModel = buildViewModel()
        viewModel.load(listOf(SummaryBlock.LOANS))
        advanceUntilIdle()
        val first = viewModel.blocks.value[SummaryBlock.LOANS]

        viewModel.load(listOf(SummaryBlock.LOANS))

        assertEquals(first, viewModel.blocks.value[SummaryBlock.LOANS])
    }

    @Test
    fun `the loans block counts them and adds the monthly payments`() = runTest {
        val viewModel = buildViewModel()

        viewModel.load(listOf(SummaryBlock.LOANS))
        advanceUntilIdle()

        val state = viewModel.blocks.value[SummaryBlock.LOANS] as BlockState.Ready
        assertEquals("2 prêts", state.lines.first())
        assertTrue(state.lines[1].endsWith("de mensualités"))
    }

    @Test
    fun `the projects block counts them, names the first and flags a dependency placed after (MAG-519)`() = runTest {
        coEvery { projects.getProjects() } returns Result.success(
            listOf(
                Project(id = "p1", name = "Déménagement", position = 0),
                Project(id = "p2", name = "Canapé", position = 1, unmetDependencies = listOf("p3")),
            ),
        )
        val viewModel = buildViewModel()

        viewModel.load(listOf(SummaryBlock.PROJECTS))
        advanceUntilIdle()

        val state = viewModel.blocks.value[SummaryBlock.PROJECTS] as BlockState.Ready
        assertEquals(listOf("2 projets", "En premier : Déménagement", "1 dépendance non respectée"), state.lines)
        assertTrue(state.needsAttention)
        // The card's visual: one mark per project in priority order, the one waiting flagged (MAG-530).
        assertEquals(listOf(false, true), state.marks)
    }

    @Test
    fun `the last synchronisation is the most recent of the banks`() = runTest {
        val viewModel = buildViewModel()

        viewModel.load(listOf(SummaryBlock.LAST_SYNC))
        advanceUntilIdle()

        val state = viewModel.blocks.value[SummaryBlock.LAST_SYNC] as BlockState.Ready
        assertTrue(state.lines.single().startsWith("Dernière synchronisation : 15/10/2026"))
    }

    @Test
    fun `the review block reads the period since the last review in one request and asks for attention while lines wait`() = runTest {
        coEvery {
            review.getList(ReviewPeriod.SINCE_REVIEW, "2026-10", ReviewState.ALL, limit = 1)
        } returns Result.success(ReviewQueue(unrecognisedCount = 4, recognisedCount = 6))
        val viewModel = buildViewModel()

        viewModel.load(listOf(SummaryBlock.REVIEW))
        advanceUntilIdle()

        val state = viewModel.blocks.value[SummaryBlock.REVIEW] as BlockState.Ready
        assertEquals(listOf("4 transactions à qualifier", "6 reconnues depuis la dernière revue"), state.lines)
        assertTrue(state.needsAttention)
    }

    @Test
    fun `the review block speaks the singular`() {
        val state = reviewBlock(ReviewQueue(unrecognisedCount = 1, recognisedCount = 1)) as BlockState.Ready

        assertEquals(listOf("1 transaction à qualifier", "1 reconnue depuis la dernière revue"), state.lines)
    }

    @Test
    fun `only recognised lines left still asks for attention`() {
        val state = reviewBlock(ReviewQueue(unrecognisedCount = 0, recognisedCount = 3)) as BlockState.Ready

        assertEquals(listOf("Rien à qualifier", "3 reconnues depuis la dernière revue"), state.lines)
        assertTrue(state.needsAttention)
    }

    @Test
    fun `an empty period says so without asking for attention`() {
        val state = reviewBlock(ReviewQueue()) as BlockState.Ready

        assertEquals(listOf("Rien à revoir"), state.lines)
        assertFalse(state.needsAttention)
    }

    @Test
    fun `a review block that cannot load says so and leaves the others`() = runTest {
        coEvery { review.getList(any(), any(), any(), any()) } returns Result.failure(IllegalStateException("réseau"))
        val viewModel = buildViewModel()

        viewModel.load(listOf(SummaryBlock.REVIEW))
        advanceUntilIdle()

        assertTrue(viewModel.blocks.value[SummaryBlock.REVIEW] is BlockState.Unavailable)
    }

    @Test
    fun `no forecast overdraft is said plainly and a forecast one asks for attention`() = runTest {
        coEvery { treasury.getOutlook() } returns Result.success(TreasuryOutlook())
        val viewModel = buildViewModel()
        viewModel.load(listOf(SummaryBlock.OUTLOOK))
        advanceUntilIdle()
        val calm = viewModel.blocks.value[SummaryBlock.OUTLOOK] as BlockState.Ready
        assertFalse(calm.needsAttention)

        coEvery { treasury.getOutlook() } returns Result.success(
            TreasuryOutlook(
                overdraft = TreasuryOverdraft(isForecast = true, onDate = "2026-10-25"),
                balanceBeforeSalary = BalanceBeforeSalary(balanceCents = -5000, onDate = "2026-10-27"),
            ),
        )
        viewModel.load(listOf(SummaryBlock.OUTLOOK))
        advanceUntilIdle()
        val alert = viewModel.blocks.value[SummaryBlock.OUTLOOK] as BlockState.Ready
        assertTrue(alert.needsAttention)
        assertEquals("Découvert prévu le 25 octobre", alert.lines.first())
    }

    @Test
    fun `a late salary marks the advances block`() {
        val late = advancesBlock(
            SavingsAdvances(
                advances = listOf(SavingsAdvance(id = "a1", outstandingCents = 100)),
                salaryDelay = SalaryDelay(isConfigured = true, isLate = true, delayDays = 3),
            ),
        ) as BlockState.Ready

        assertTrue(late.needsAttention)
        assertEquals(listOf("Aucune avance en cours"), (advancesBlock(SavingsAdvances()) as BlockState.Ready).lines)
    }

    @Test
    fun `the banks block says when a consent has to be renewed`() {
        val ok = banksBlock(listOf(BankConnection(id = "b1", bankName = "A", status = "active"))) as BlockState.Ready
        val renew = banksBlock(
            listOf(BankConnection(id = "b1", bankName = "A", status = "active", needsReconnecting = true)),
        ) as BlockState.Ready

        assertEquals(listOf("1 banque", "Consentements à jour"), ok.lines)
        assertFalse(ok.needsAttention)
        assertEquals("1 à reconnecter", renew.lines[1])
        assertTrue(renew.needsAttention)
        assertEquals(listOf("Aucune banque connectée"), (banksBlock(emptyList()) as BlockState.Ready).lines)
    }

    @Test
    fun `an unconfigured cushion says so instead of showing zeros`() {
        assertEquals(listOf("Pas encore réglé"), (cushionBlock(CushionStatus()) as BlockState.Ready).lines)
    }

    @Test
    fun `nothing is loaded before a page asks`() {
        assertNull(buildViewModel().blocks.value[SummaryBlock.CUSHION])
    }
}

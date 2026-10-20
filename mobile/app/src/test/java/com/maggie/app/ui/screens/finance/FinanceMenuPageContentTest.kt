package com.maggie.app.ui.screens.finance

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.BankConnection
import com.maggie.app.data.model.Category
import com.maggie.app.data.model.CushionStatus
import com.maggie.app.data.model.FinanceDashboard
import com.maggie.app.data.model.FinanceTiles
import com.maggie.app.data.model.IndependenceCounter
import com.maggie.app.data.model.Loan
import com.maggie.app.data.model.PlanningTile
import com.maggie.app.data.model.SavingsAdvance
import com.maggie.app.data.model.SavingsAdvances
import com.maggie.app.data.repository.BankConnectionRepository
import com.maggie.app.data.repository.CategorizationRuleRepository
import com.maggie.app.data.repository.CategoryRepository
import com.maggie.app.data.repository.CushionRepository
import com.maggie.app.data.repository.FinanceDashboardRepository
import com.maggie.app.data.repository.LoanRepository
import com.maggie.app.data.repository.ProjectRepository
import com.maggie.app.data.repository.MonthlyReviewRepository
import com.maggie.app.data.repository.RecurringOperationRepository
import com.maggie.app.data.repository.SavingsAdvancesRepository
import com.maggie.app.data.repository.TransactionRepository
import com.maggie.app.data.repository.TreasuryRepository
import com.maggie.app.screentest.FakeBankConnections
import com.maggie.app.screentest.FakeCategories
import com.maggie.app.screentest.FakeSavingsAdvances
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.navigation.FinanceMenu
import io.mockk.coEvery
import io.mockk.mockk
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * The menu pages with the real content of their aspects under the tabs (MAG-518): what the owner
 * sees on opening Planification, Configuration or Trésorerie — the loans themselves, the banks,
 * the categories, the cushion — never a counter standing for them, and an invitation to act where
 * nothing has been entered yet.
 */
@RunWith(AndroidJUnit4::class)
class FinanceMenuPageContentTest {

    @get:Rule
    val compose = ScreenRule()

    private fun summary(
        loans: LoanRepository = mockk(),
        projects: ProjectRepository = noProjects(),
        dashboard: FinanceDashboardRepository = mockk(),
        banks: BankConnectionRepository = mockk(),
        categories: CategoryRepository = mockk(),
        cushion: CushionRepository = mockk(),
        advances: SavingsAdvancesRepository = mockk(),
    ) = FinanceSummaryViewModel(
        cushion, advances, mockk<TreasuryRepository>(), mockk<MonthlyReviewRepository>(),
        mockk<CategorizationRuleRepository>(), mockk<RecurringOperationRepository>(), loans, projects, dashboard, banks, categories,
        mockk<TransactionRepository>(), today = { LocalDate.of(2026, 10, 16) },
    )

    private fun noProjects(): ProjectRepository {
        val repository = mockk<ProjectRepository>()
        coEvery { repository.getProjects() } returns Result.success(emptyList())
        return repository
    }

    private fun loansOf(vararg loan: Loan): LoanRepository {
        val repository = mockk<LoanRepository>()
        coEvery { repository.getLoans() } returns Result.success(loan.toList())
        coEvery { repository.getTimeline(any()) } returns Result.failure(IllegalStateException("pas d'échéancier"))
        return repository
    }

    private fun dashboardOf(counter: IndependenceCounter): FinanceDashboardRepository {
        val repository = mockk<FinanceDashboardRepository>()
        coEvery { repository.getDashboard(any(), any()) } returns Result.success(
            FinanceDashboard(tiles = FinanceTiles(planning = PlanningTile(independence = counter))),
        )
        return repository
    }

    @Composable
    private fun PlanningPage(loans: LoanRepository, dashboard: FinanceDashboardRepository, summary: FinanceSummaryViewModel) {
        val loanViewModel = LoanViewModel(loans)
        val independenceViewModel = IndependenceViewModel(dashboard) { LocalDate.of(2026, 10, 16) }
        FinanceMenuPage(FinanceMenu.PLANNING, summary, requestedTab = null, onBack = {}) { aspect, _, modifier ->
            when (aspect.id) {
                "loans" -> LoanListContent(loanViewModel, modifier)
                "independence" -> IndependenceContent(independenceViewModel, onDeclareRente = {}, modifier = modifier)
                // The projects have their own tests (ProjectListScreenTest): an empty body here.
                else -> Box(modifier)
            }
        }
    }

    @Test
    fun `planning with a loan opens on it, with the loan itself and not a count`() {
        val loans = loansOf(Loan(id = "l1", name = "Voiture", monthlyPaymentCents = 25000))
        val dashboard = dashboardOf(IndependenceCounter())
        compose.setContent {
            val summary = remember { summary(loans = loans, dashboard = dashboard) }
            PlanningPage(loans, dashboard, summary)
        }
        compose.waitForIdle()

        compose.onNodeWithTag(UiTags.financeTab("loans")).assertIsDisplayed()
        compose.onNodeWithText("Voiture").assertIsDisplayed()
    }

    @Test
    fun `planning without data invites to add a loan on its tab, and to declare a rente on the other`() {
        val loans = loansOf()
        val dashboard = dashboardOf(IndependenceCounter())
        compose.setContent {
            val summary = remember { summary(loans = loans, dashboard = dashboard) }
            PlanningPage(loans, dashboard, summary)
        }
        compose.waitForIdle()

        compose.onNodeWithTag(UiTags.financeTab("loans")).performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Aucun prêt enregistré").assertIsDisplayed()

        compose.onNodeWithTag(UiTags.financeTab("independence")).performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Déclarer une rente").assertIsDisplayed()
    }

    @Test
    fun `planning with only a rente opens on the independence, measured`() {
        val loans = loansOf()
        val dashboard = dashboardOf(
            IndependenceCounter(
                hasPassiveIncomeCategories = true,
                isMeasurable = true,
                coveragePercent = 62,
                lifestyleCents = 10000,
                passiveIncomeCents = 6200,
                gapCents = 3800,
            ),
        )
        compose.setContent {
            val summary = remember { summary(loans = loans, dashboard = dashboard) }
            PlanningPage(loans, dashboard, summary)
        }
        compose.waitForIdle()

        compose.onNodeWithText("62 %").assertIsDisplayed()
    }

    @Composable
    private fun SettingsPage(banks: FakeBankConnections, categories: FakeCategories, summary: FinanceSummaryViewModel) {
        FinanceMenuPage(FinanceMenu.SETTINGS, summary, requestedTab = null, onBack = {}) { aspect, _, modifier ->
            when (aspect.id) {
                "banks" -> BankConnectionListContent(banks.viewModel, modifier)
                else -> CategoryListContent(categories.viewModel, modifier)
            }
        }
    }

    private fun categoriesRepository(count: Int): CategoryRepository {
        val repository = mockk<CategoryRepository>()
        coEvery { repository.getCategories() } returns Result.success(
            List(count) { Category(id = "c$it", name = "Courses") },
        )
        return repository
    }

    private fun banksRepository(vararg bank: BankConnection): BankConnectionRepository {
        val repository = mockk<BankConnectionRepository>()
        coEvery { repository.getConnections() } returns Result.success(bank.toList())
        return repository
    }

    @Test
    fun `configuration shows the banks, then the categories`() {
        val bankRepository = banksRepository(BankConnection(id = "b1", bankName = "Mock Bank", status = "active"))
        val categoryRepository = categoriesRepository(1)
        val categories = FakeCategories()
        compose.setContent {
            val summary = remember { summary(banks = bankRepository, categories = categoryRepository) }
            SettingsPage(FakeBankConnections(listOf(BankConnection(id = "b1", bankName = "Mock Bank", status = "active"))), categories, summary)
        }
        compose.waitForIdle()

        compose.onNodeWithText("Mock Bank").assertIsDisplayed()

        compose.onNodeWithTag(UiTags.financeTab("categories")).performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Courses").assertIsDisplayed()
    }

    @Test
    fun `configuration with nothing entered says how to start, tab by tab`() {
        val bankRepository = banksRepository()
        val categoryRepository = categoriesRepository(0)
        compose.setContent {
            val summary = remember { summary(banks = bankRepository, categories = categoryRepository) }
            SettingsPage(FakeBankConnections(emptyList()), FakeCategories(initial = emptyList()), summary)
        }
        compose.waitForIdle()

        compose.onNodeWithText("Aucune banque connectée").assertIsDisplayed()

        compose.onNodeWithTag(UiTags.financeTab("categories")).performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Aucune catégorie pour l'instant").assertIsDisplayed()
    }

    @Composable
    private fun TreasuryPage(cushion: CushionViewModel, advances: FakeSavingsAdvances, summary: FinanceSummaryViewModel) {
        FinanceMenuPage(
            FinanceMenu.TREASURY, summary, requestedTab = null, onBack = {},
            aspects = financeAspects(FinanceMenu.TREASURY).filter { it.id != "outlook" },
        ) { aspect, _, modifier ->
            when (aspect.id) {
                "cushion" -> CushionContent(cushion, modifier)
                else -> SavingsAdvancesContent(advances.viewModel, modifier)
            }
        }
    }

    private fun cushionRepository(status: CushionStatus): CushionRepository {
        val repository = mockk<CushionRepository>()
        coEvery { repository.getStatus() } returns Result.success(status)
        return repository
    }

    private fun advancesRepository(advances: SavingsAdvances): SavingsAdvancesRepository {
        val repository = mockk<SavingsAdvancesRepository>()
        coEvery { repository.getSavingsAdvances() } returns Result.success(advances)
        return repository
    }

    @Test
    fun `treasury opens on the cushion when it is set, with its figures, and shows the advances on the next tab`() {
        val status = CushionStatus(isConfigured = true, targetCents = 900000, currentCents = 450000)
        val withAdvance = SavingsAdvances(
            advances = listOf(SavingsAdvance("adv-1", "acc-livret", "Livret A", 150000, "2026-10-11", 100000, 50000, false)),
            totalOutstandingCents = 50000,
        )
        val cushionRepository = cushionRepository(status)
        val advancesRepository = advancesRepository(withAdvance)
        compose.setContent {
            val summary = remember { summary(cushion = cushionRepository, advances = advancesRepository) }
            val cushion = remember { CushionViewModel(cushionRepository) }
            TreasuryPage(cushion, FakeSavingsAdvances(withAdvance), summary)
        }
        compose.waitForIdle()

        compose.onNodeWithText("Matelas de sécurité").assertIsDisplayed()

        compose.onNodeWithTag(UiTags.financeTab("advances")).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(UiTags.SAVINGS_ADVANCES).assertIsDisplayed()
        compose.onNodeWithText("Livret A").assertIsDisplayed()
    }

    @Test
    fun `treasury without anything set opens on its first tab, and the advances tab says there is none`() {
        val cushionRepository = cushionRepository(CushionStatus())
        val advancesRepository = advancesRepository(SavingsAdvances())
        compose.setContent {
            val summary = remember { summary(cushion = cushionRepository, advances = advancesRepository) }
            val cushion = remember { CushionViewModel(cushionRepository) }
            TreasuryPage(cushion, FakeSavingsAdvances(SavingsAdvances()), summary)
        }
        compose.waitForIdle()

        compose.onNodeWithText("Matelas de sécurité").assertIsDisplayed()

        compose.onNodeWithTag(UiTags.financeTab("advances")).performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Aucune avance en cours").assertIsDisplayed()
    }
}

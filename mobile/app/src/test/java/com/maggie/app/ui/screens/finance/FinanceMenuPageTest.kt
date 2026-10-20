package com.maggie.app.ui.screens.finance

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.CushionStatus
import com.maggie.app.data.model.FinanceDashboard
import com.maggie.app.data.model.FinanceTiles
import com.maggie.app.data.model.IndependenceCounter
import com.maggie.app.data.model.Loan
import com.maggie.app.data.model.PlanningTile
import com.maggie.app.data.model.Project
import com.maggie.app.data.model.RecognisedToControl
import com.maggie.app.data.model.RecurringOperationsOverview
import com.maggie.app.data.model.SavingsAdvances
import com.maggie.app.data.model.MonthlyReview
import com.maggie.app.data.model.TreasuryOutlook
import com.maggie.app.data.model.ToSortPile
import com.maggie.app.data.repository.BankConnectionRepository
import com.maggie.app.data.repository.CategorizationRuleRepository
import com.maggie.app.data.repository.CategoryRepository
import com.maggie.app.data.repository.CushionRepository
import com.maggie.app.data.repository.FinanceDashboardRepository
import com.maggie.app.data.repository.LoanRepository
import com.maggie.app.data.repository.MonthlyReviewRepository
import com.maggie.app.data.repository.ProjectRepository
import com.maggie.app.data.repository.RecurringOperationRepository
import com.maggie.app.data.repository.SavingsAdvancesRepository
import com.maggie.app.data.repository.TransactionRepository
import com.maggie.app.data.repository.TreasuryRepository
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.navigation.FinanceMenu
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * The page of a Finance menu (MAG-518): one tab per aspect, the content of the selected one under
 * the tabs. Which tab opens, which one is asked for by the route, which one survives a rotation.
 *
 * The content under the tabs is a stand-in here (it names the aspect it is drawn for): each
 * aspect's own content is tested with its screen, and `FinanceMenuPageContentTest` wires real ones.
 */
@RunWith(AndroidJUnit4::class)
class FinanceMenuPageTest {

    @get:Rule
    val compose = ScreenRule()

    /** A server where nothing has been entered yet, every block answering « no data ». */
    private class Backend {
        val cushion = mockk<CushionRepository>()
        val advances = mockk<SavingsAdvancesRepository>()
        val treasury = mockk<TreasuryRepository>()
        val review = mockk<MonthlyReviewRepository>()
        val rules = mockk<CategorizationRuleRepository>()
        val recurring = mockk<RecurringOperationRepository>()
        val loans = mockk<LoanRepository>()
        val projects = mockk<ProjectRepository>()
        val dashboard = mockk<FinanceDashboardRepository>()
        val banks = mockk<BankConnectionRepository>()
        val categories = mockk<CategoryRepository>()
        val transactions = mockk<TransactionRepository>()

        init {
            coEvery { cushion.getStatus() } returns Result.success(CushionStatus())
            coEvery { advances.getSavingsAdvances() } returns Result.success(SavingsAdvances())
            coEvery { treasury.getOutlook() } returns Result.success(TreasuryOutlook())
            coEvery { transactions.getToSort() } returns Result.success(ToSortPile(total = 0))
            coEvery { transactions.getRecognisedToControl(any()) } returns Result.success(RecognisedToControl(total = 0))
            coEvery { review.getReview(any(), any()) } returns Result.success(MonthlyReview())
            coEvery { rules.getRules() } returns Result.success(emptyList())
            coEvery { recurring.getOverview() } returns Result.success(RecurringOperationsOverview())
            coEvery { loans.getLoans() } returns Result.success(emptyList())
            coEvery { projects.getProjects() } returns Result.success(emptyList())
            coEvery { dashboard.getDashboard(any(), any()) } returns Result.success(FinanceDashboard())
            coEvery { banks.getConnections() } returns Result.success(emptyList())
            coEvery { categories.getCategories() } returns Result.success(emptyList())
        }

        val viewModel: FinanceSummaryViewModel by lazy {
            FinanceSummaryViewModel(
                cushion, advances, treasury, review, rules, recurring, loans, projects, dashboard, banks, categories, transactions,
                today = { LocalDate.of(2026, 10, 16) },
            )
        }
    }

    private fun independence(rentes: Boolean) = Result.success(
        FinanceDashboard(
            tiles = FinanceTiles(planning = PlanningTile(independence = IndependenceCounter(hasPassiveIncomeCategories = rentes))),
        ),
    )

    @Composable
    private fun Page(
        menu: FinanceMenu,
        viewModel: FinanceSummaryViewModel,
        requestedTab: String? = null,
        onBack: () -> Unit = {},
        aspects: List<FinanceAspect> = financeAspects(menu),
    ) {
        FinanceMenuPage(menu, viewModel, requestedTab, onBack, aspects) { aspect, _, modifier ->
            Text("contenu ${aspect.id}", modifier.testTag("content_${aspect.id}"))
        }
    }

    private fun assertOpenOn(menu: FinanceMenu, id: String) {
        compose.waitForIdle()
        compose.onNodeWithTag("content_$id").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.financeTab(id)).assertIsSelected()
        financeAspects(menu).filter { it.id != id }.forEach {
            compose.onNodeWithTag(UiTags.financeTab(it.id)).assertIsNotSelected()
            compose.onNodeWithTag("content_${it.id}").assertDoesNotExist()
        }
    }

    private fun assertEmptyMenuShowsAllItsTabs(menu: FinanceMenu) {
        val viewModel = Backend().viewModel
        compose.setContent { Page(menu, viewModel) }
        compose.waitForIdle()

        financeAspects(menu).forEach {
            compose.onNodeWithTag(UiTags.financeTab(it.id)).assertIsDisplayed()
        }
        // Empty everywhere: the menu opens on its first aspect, never on a blank page.
        compose.onNodeWithTag("content_${financeAspects(menu).first().id}").assertIsDisplayed()
    }

    @Test
    fun `the review page has a tab per aspect, and opens on the first when it is empty`() =
        assertEmptyMenuShowsAllItsTabs(FinanceMenu.REVIEW)

    @Test
    fun `the treasury page has a tab per aspect, and opens on the first when it is empty`() =
        assertEmptyMenuShowsAllItsTabs(FinanceMenu.TREASURY)

    @Test
    fun `the planning page has a tab per aspect, and opens on the first when it is empty`() =
        assertEmptyMenuShowsAllItsTabs(FinanceMenu.PLANNING)

    @Test
    fun `the configuration page has a tab per aspect, and opens on the first when it is empty`() =
        assertEmptyMenuShowsAllItsTabs(FinanceMenu.SETTINGS)

    @Test
    fun `each menu lists its aspects under stable ids, in reading order`() {
        assertEquals(
            listOf("to-sort", "to-control", "monthly-review", "rules", "recurring"),
            financeAspects(FinanceMenu.REVIEW).map { it.id },
        )
        assertEquals(listOf("outlook", "cushion", "advances"), financeAspects(FinanceMenu.TREASURY).map { it.id })
        assertEquals(listOf("projects", "loans", "independence"), financeAspects(FinanceMenu.PLANNING).map { it.id })
        assertEquals(listOf("banks", "categories"), financeAspects(FinanceMenu.SETTINGS).map { it.id })
        listOf(FinanceMenu.DASHBOARD, FinanceMenu.ACCOUNTS, FinanceMenu.BUDGET).forEach {
            assertTrue(financeAspects(it).isEmpty())
        }
    }

    @Test
    fun `an aspect id names one aspect of the app`() {
        val all = FinanceMenu.entries.flatMap(::financeAspects)

        assertEquals(all.size, all.map { it.id }.toSet().size)
        assertEquals(all.size, all.map { it.block }.toSet().size)
    }

    @Test
    fun `the page opens on the first aspect that has data`() {
        val backend = Backend()
        coEvery { backend.cushion.getStatus() } returns Result.success(CushionStatus(isConfigured = true, targetCents = 900000))
        compose.setContent { Page(FinanceMenu.TREASURY, backend.viewModel) }

        assertOpenOn(FinanceMenu.TREASURY, "cushion")
    }

    @Test
    fun `planning opens on the loans when there is a loan`() {
        val backend = Backend()
        coEvery { backend.loans.getLoans() } returns Result.success(listOf(Loan(id = "l1", name = "Voiture")))
        coEvery { backend.dashboard.getDashboard(any(), any()) } returns independence(rentes = true)
        compose.setContent { Page(FinanceMenu.PLANNING, backend.viewModel) }

        assertOpenOn(FinanceMenu.PLANNING, "loans")
    }

    @Test
    fun `planning opens on the independence when only a rente is declared`() {
        val backend = Backend()
        coEvery { backend.dashboard.getDashboard(any(), any()) } returns independence(rentes = true)
        compose.setContent { Page(FinanceMenu.PLANNING, backend.viewModel) }

        assertOpenOn(FinanceMenu.PLANNING, "independence")
    }

    @Test
    fun `planning opens on its first aspect when none has data`() {
        val viewModel = Backend().viewModel
        compose.setContent { Page(FinanceMenu.PLANNING, viewModel) }

        assertOpenOn(FinanceMenu.PLANNING, "projects")
    }

    @Test
    fun `planning opens on the projects as soon as there is one, loans or not (MAG-519)`() {
        val backend = Backend()
        coEvery { backend.projects.getProjects() } returns Result.success(listOf(Project(id = "p1", name = "Canapé")))
        coEvery { backend.loans.getLoans() } returns Result.success(listOf(Loan(id = "l1", name = "Voiture")))
        compose.setContent { Page(FinanceMenu.PLANNING, backend.viewModel) }

        assertOpenOn(FinanceMenu.PLANNING, "projects")
    }

    @Test
    fun `review skips the aspects with nothing in them`() {
        val backend = Backend()
        coEvery { backend.transactions.getRecognisedToControl(any()) } returns Result.success(RecognisedToControl(total = 3))
        compose.setContent { Page(FinanceMenu.REVIEW, backend.viewModel) }

        assertOpenOn(FinanceMenu.REVIEW, "to-control")
    }

    @Test
    fun `an aspect whose figures failed does not count as having data`() {
        val backend = Backend()
        coEvery { backend.loans.getLoans() } returns Result.failure(IllegalStateException("Service indisponible"))
        coEvery { backend.dashboard.getDashboard(any(), any()) } returns independence(rentes = true)
        compose.setContent { Page(FinanceMenu.PLANNING, backend.viewModel) }

        assertOpenOn(FinanceMenu.PLANNING, "independence")
    }

    @Test
    fun `the tab asked for by the route wins over the first aspect with data`() {
        val backend = Backend()
        coEvery { backend.loans.getLoans() } returns Result.success(listOf(Loan(id = "l1", name = "Voiture")))
        compose.setContent { Page(FinanceMenu.PLANNING, backend.viewModel, requestedTab = "independence") }

        assertOpenOn(FinanceMenu.PLANNING, "independence")
    }

    @Test
    fun `a tab the menu does not have is ignored`() {
        val backend = Backend()
        coEvery { backend.dashboard.getDashboard(any(), any()) } returns independence(rentes = true)
        compose.setContent { Page(FinanceMenu.PLANNING, backend.viewModel, requestedTab = "cushion") }

        assertOpenOn(FinanceMenu.PLANNING, "independence")
    }

    @Test
    fun `a tap on a tab shows the content of the other aspect`() {
        val backend = Backend()
        coEvery { backend.cushion.getStatus() } returns Result.success(CushionStatus(isConfigured = true, targetCents = 900000))
        compose.setContent { Page(FinanceMenu.TREASURY, backend.viewModel) }
        assertOpenOn(FinanceMenu.TREASURY, "cushion")

        compose.onNodeWithTag(UiTags.financeTab("advances")).performClick()
        assertOpenOn(FinanceMenu.TREASURY, "advances")

        compose.onNodeWithTag(UiTags.financeTab("outlook")).performClick()
        assertOpenOn(FinanceMenu.TREASURY, "outlook")
    }

    @Test
    fun `the page asks for the figures of its own aspects only`() {
        val backend = Backend()
        compose.setContent { Page(FinanceMenu.TREASURY, backend.viewModel) }
        compose.waitForIdle()

        coVerify(exactly = 0) { backend.loans.getLoans() }
        coVerify(exactly = 0) { backend.banks.getConnections() }
    }

    @Test
    fun `the tab picked survives a rotation, and the data arriving later does not move it`() {
        val backend = Backend()
        val restoration = StateRestorationTester(compose)
        restoration.setContent { Page(FinanceMenu.TREASURY, backend.viewModel) }
        compose.waitForIdle()
        compose.onNodeWithTag(UiTags.financeTab("advances")).performClick()
        assertOpenOn(FinanceMenu.TREASURY, "advances")

        restoration.emulateSavedInstanceStateRestore()

        assertOpenOn(FinanceMenu.TREASURY, "advances")
    }

    @Test
    fun `the default tab, once known, survives a rotation even if the data changed`() {
        val backend = Backend()
        coEvery { backend.cushion.getStatus() } returns Result.success(CushionStatus(isConfigured = true, targetCents = 900000))
        val restoration = StateRestorationTester(compose)
        restoration.setContent { Page(FinanceMenu.TREASURY, backend.viewModel) }
        assertOpenOn(FinanceMenu.TREASURY, "cushion")

        coEvery { backend.cushion.getStatus() } returns Result.success(CushionStatus())
        restoration.emulateSavedInstanceStateRestore()

        assertOpenOn(FinanceMenu.TREASURY, "cushion")
    }

    @Test
    fun `a short loading state shows until the first aspect with data is known, and tabs stay usable`() {
        val backend = Backend()
        coEvery { backend.transactions.getToSort() } coAnswers { awaitCancellation() }
        compose.setContent { Page(FinanceMenu.REVIEW, backend.viewModel) }
        compose.waitForIdle()

        compose.onNodeWithTag(UiTags.FINANCE_ASPECT_LOADING).assertIsDisplayed()
        compose.onNodeWithTag("content_to-sort").assertDoesNotExist()

        compose.onNodeWithTag(UiTags.financeTab("rules")).performClick()

        compose.onNodeWithTag(UiTags.FINANCE_ASPECT_LOADING).assertDoesNotExist()
        assertOpenOn(FinanceMenu.REVIEW, "rules")
    }

    @Test
    fun `a menu with a single aspect shows no tabs`() {
        val only = FinanceAspect("cushion", "Matelas", SummaryBlock.CUSHION)
        val viewModel = Backend().viewModel
        compose.setContent { Page(FinanceMenu.TREASURY, viewModel, aspects = listOf(only)) }
        compose.waitForIdle()

        compose.onNodeWithTag("content_cushion").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.financeTab("cushion")).assertDoesNotExist()
    }

    @Test
    fun `the back arrow asks to leave the page`() {
        var backs = 0
        val viewModel = Backend().viewModel
        compose.setContent { Page(FinanceMenu.TREASURY, viewModel, onBack = { backs++ }) }

        compose.onNodeWithContentDescription("Retour").performClick()

        assertEquals(1, backs)
    }

    @Test
    fun `no default is chosen while an earlier aspect is still loading`() {
        val aspects = financeAspects(FinanceMenu.PLANNING)

        assertNull(defaultAspectId(aspects, mapOf(SummaryBlock.LOANS to BlockState.Loading)))
        assertNull(defaultAspectId(aspects, emptyMap()))
        assertEquals(
            "independence",
            defaultAspectId(
                aspects,
                mapOf(
                    SummaryBlock.LOANS to BlockState.Ready(listOf("Aucun prêt")),
                    SummaryBlock.INDEPENDENCE to BlockState.Ready(listOf("x"), hasData = true),
                ),
            ),
        )
    }
}

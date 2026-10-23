package com.maggie.app.ui.screens.finance

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.filter
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.model.PlannedInvestment
import com.maggie.app.data.model.PlannedLoan
import com.maggie.app.data.model.PlannedProject
import com.maggie.app.data.model.PlanningMonth
import com.maggie.app.data.model.Project
import com.maggie.app.data.model.ProjectInvestment
import com.maggie.app.data.model.ProjectPlanning
import com.maggie.app.data.model.ProjectPreview
import com.maggie.app.data.model.ProjectPreviewDate
import com.maggie.app.data.model.ProjectPreviewMetrics
import com.maggie.app.data.model.ProjectPreviewN
import com.maggie.app.data.model.ProjectPreviewProject
import com.maggie.app.data.model.ProjectPreviewScenario
import com.maggie.app.data.model.ProjectResale
import com.maggie.app.data.model.formatCents
import com.maggie.app.data.model.ProjectFundingSource
import com.maggie.app.data.repository.ProjectRepository
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.screentest.assertTopToBottom
import com.maggie.app.ui.UiTags
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * « Projets » as drawn (MAG-519): the rows in priority order with their badge, date, dependency and
 * warning; the empty state; a tap that opens the form; the move a row offers to accessibility; and
 * the form that will not send a fixed date without its date. The drag itself is the journey's
 * (`e2e/mobile/flows/23-finance-projects.yaml`): a finger on a lazy list is a device question.
 */
@RunWith(AndroidJUnit4::class)
class ProjectListScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private val repository = mockk<ProjectRepository>()

    private val move = Project(
        id = "p1", name = "Déménagement", amountCents = 250000, wellbeing = "essential", wellbeingScore = 4,
        position = 0, dateMode = "fixed", targetOn = "2027-03-01",
    )
    private val sofa = Project(
        id = "p2", name = "Canapé", amountCents = 120000, wellbeing = "comfort", wellbeingScore = 1,
        position = 1, dependsOn = listOf("/api/finance/projects/p1"),
    )

    private val planning = ProjectPlanning(
        startMonth = "2026-10",
        isIncomeKnown = true,
        projects = listOf(
            PlannedProject(projectId = "p1", name = "Déménagement", position = 0, amountCents = 250000, gatheredCents = 50000, dateMode = "fixed", targetOn = "2027-03-01", savingFromMonth = "2026-10", purchaseMonth = "2027-03"),
            PlannedProject(projectId = "p2", name = "Canapé", position = 1, amountCents = 120000, savingFromMonth = "2027-03", purchaseMonth = "2027-06"),
        ),
    )

    private fun viewModel(
        projects: List<Project> = listOf(move, sofa),
        plan: Result<ProjectPlanning> = Result.success(planning),
    ): ProjectViewModel {
        coEvery { repository.getProjects() } returns Result.success(projects)
        coEvery { repository.getProjectPlanning() } returns plan
        val auth = mockk<AuthRepository>()
        coEvery { auth.getUserId() } returns "user-1"
        val mercure = mockk<MercureService>()
        every { mercure.subscribe(any()) } returns emptyFlow()
        return ProjectViewModel(repository, mercure, auth)
    }

    @Test
    fun `the projects read in priority order, each with its badge, its date and what it waits for`() {
        compose.setContent { ProjectListScreen(viewModel(), onBack = {}, onOpenForm = {}) }

        compose.waitUntil(5_000) { compose.onAllNodesWithTag(UiTags.PROJECT_ROW).fetchSemanticsNodes().size == 2 }
        assertTopToBottom(
            "Déménagement" to compose.onNodeWithText("1. Déménagement"),
            "Canapé" to compose.onNodeWithText("2. Canapé"),
        )
        compose.onNodeWithText("Indispensable").assertIsDisplayed()
        compose.onNodeWithText("Confort").assertIsDisplayed()
        compose.onNodeWithText("Date fixe · 1 mars 2027", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Sans date", substring = true).assertIsDisplayed()
        compose.onNodeWithText("après Déménagement").assertIsDisplayed()
    }

    @Test
    fun `a project placed before the one it waits for carries the warning`() {
        val ahead = sofa.copy(position = 0, unmetDependencies = listOf("p1"))
        compose.setContent { ProjectListScreen(viewModel(listOf(ahead, move.copy(position = 1))), onBack = {}, onOpenForm = {}) }

        compose.waitUntil(5_000) { compose.onAllNodesWithTag(UiTags.PROJECT_UNMET, useUnmergedTree = true).fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText("Dépendance non respectée").assertIsDisplayed()
    }

    @Test
    fun `without a project the screen offers to add one`() {
        val opened = mutableListOf<String?>()
        compose.setContent { ProjectListScreen(viewModel(emptyList()), onBack = {}, onOpenForm = { opened += it }) }

        compose.waitUntil(5_000) { compose.onAllNodesWithTag(UiTags.PROJECT_EMPTY).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Ajouter un projet").performClick()

        assertEquals(listOf<String?>(null), opened)
    }

    @Test
    fun `a tap on a row opens its form, the button a new one`() {
        val opened = mutableListOf<String?>()
        compose.setContent { ProjectListScreen(viewModel(), onBack = {}, onOpenForm = { opened += it }) }

        compose.waitUntil(5_000) { compose.onAllNodesWithTag(UiTags.PROJECT_ROW).fetchSemanticsNodes().size == 2 }
        compose.onNodeWithText("2. Canapé").performClick()
        compose.onNodeWithTag(UiTags.PROJECT_ADD).performClick()

        assertEquals(listOf("p2", null), opened)
    }

    @Test
    fun `the move up a row offers reorders the list and sends the order`() {
        coEvery { repository.reorderProjects(listOf("p2", "p1")) } returns
            Result.success(listOf(sofa.copy(position = 0), move.copy(position = 1)))
        compose.setContent { ProjectListScreen(viewModel(), onBack = {}, onOpenForm = {}) }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(UiTags.PROJECT_ROW).fetchSemanticsNodes().size == 2 }

        val row = compose.onAllNodesWithTag(UiTags.PROJECT_ROW).filter(hasText("2. Canapé", substring = true)).fetchSemanticsNodes().single()
        val up = row.config[SemanticsActions.CustomActions].single { it.label == "Monter Canapé" }
        compose.runOnUiThread { up.action() }
        compose.waitForIdle()

        assertTopToBottom(
            "Canapé" to compose.onNodeWithText("1. Canapé"),
            "Déménagement" to compose.onNodeWithText("2. Déménagement"),
        )
        coVerify { repository.reorderProjects(listOf("p2", "p1")) }
    }

    @Test
    fun `the form will not send a fixed date without its date`() {
        val viewModel = viewModel()
        compose.setContent { ProjectFormScreen(viewModel, projectId = null, onClose = {}) }

        compose.waitUntil(5_000) { compose.onAllNodesWithTag(UiTags.PROJECT_NAME).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(UiTags.PROJECT_NAME).performTextInput("Vélo")
        compose.onNodeWithTag(UiTags.PROJECT_AMOUNT).performTextInput("900")
        compose.onNodeWithTag(UiTags.projectDateMode("fixed")).performClick()
        compose.onNodeWithTag(UiTags.PROJECT_SAVE).performClick()

        compose.onNodeWithText("Choisissez la date du projet.").assertIsDisplayed()
        coVerify(exactly = 0) { repository.createProject(any()) }
    }

    private fun preview(editable: Boolean = true) = ProjectPreview(
        scenario = ProjectPreviewScenario("s1", "Équilibré", editable),
        project = ProjectPreviewProject(name = "Box à louer", amountCents = 1800000, currency = "EUR"),
        dates = listOf(
            ProjectPreviewDate("p1", "Déménagement", before = "2027-03", after = "2027-03"),
            ProjectPreviewDate("p2", "Canapé", before = "2027-06", after = "2027-07"),
        ),
        nDate = ProjectPreviewN(before = "2041-03", after = "2041-05"),
        metrics = ProjectPreviewMetrics(realCost = 1800000, availableAt = "2028-01", nImpactMonths = 2),
    )

    private fun fillBoxAndAsk(viewModel: ProjectViewModel) {
        compose.setContent { ProjectFormScreen(viewModel, projectId = null, onClose = {}) }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(UiTags.PROJECT_NAME).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(UiTags.PROJECT_NAME).performTextInput("Box à louer")
        compose.onNodeWithTag(UiTags.PROJECT_AMOUNT).performTextInput("18000")
        compose.onNodeWithTag(UiTags.PROJECT_SAVE).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(UiTags.PROJECT_PREVIEW).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun `on the tablet a new project is previewed first, its dates before and after and N`() {
        coEvery { repository.previewProject(any()) } returns Result.success(preview())
        fillBoxAndAsk(viewModel())

        compose.onNodeWithText("Ajouter « Box à louer » au scénario courant ?").assertIsDisplayed()
        compose.onAllNodesWithTag(UiTags.PROJECT_PREVIEW_DATE).assertCountEquals(2)
        compose.onAllNodesWithTag(UiTags.PROJECT_PREVIEW_N).assertCountEquals(1)
        compose.onNodeWithText("juin 2027").assertIsDisplayed()
        compose.onNodeWithText("juillet 2027").assertIsDisplayed()
        compose.onNodeWithText("Coût réel : ", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Impact sur N : 2 mois plus tard").assertIsDisplayed()
        coVerify(exactly = 0) { repository.createProject(any()) }
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun `cancelling the preview creates nothing`() {
        coEvery { repository.previewProject(any()) } returns Result.success(preview())
        fillBoxAndAsk(viewModel())

        compose.onNodeWithTag(UiTags.PROJECT_PREVIEW_CANCEL).performClick()

        compose.waitUntil(5_000) { compose.onAllNodesWithTag(UiTags.PROJECT_PREVIEW).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag(UiTags.PROJECT_SAVE).assertTextEquals("Voir l'aperçu")
        coVerify(exactly = 0) { repository.createProject(any()) }
        coVerify(exactly = 0) { repository.addToScenario(any(), any()) }
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun `adding from the preview creates the project and puts it in the scenario`() {
        val created = Project(id = "p3", name = "Box à louer", amountCents = 1800000, position = 2)
        coEvery { repository.previewProject(any()) } returns Result.success(preview())
        coEvery { repository.createProject(any()) } returns Result.success(created)
        coEvery { repository.addToScenario("s1", "p3") } returns Result.success(Unit)
        fillBoxAndAsk(viewModel())

        compose.onNodeWithTag(UiTags.PROJECT_PREVIEW_CONFIRM).performClick()

        compose.waitUntil(5_000) { compose.onAllNodesWithTag(UiTags.PROJECT_PREVIEW).fetchSemanticsNodes().isEmpty() }
        coVerify(exactly = 1) { repository.createProject(any()) }
        coVerify(exactly = 1) { repository.addToScenario("s1", "p3") }
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun `an automatic scenario shows why the add waits`() {
        coEvery { repository.previewProject(any()) } returns Result.success(preview(editable = false))
        fillBoxAndAsk(viewModel())

        compose.onNodeWithText("dupliquez-la", substring = true).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.PROJECT_PREVIEW_CONFIRM).assertIsNotEnabled()
    }

    @Test
    fun `on the phone a new project is created as before, without a preview`() {
        val created = Project(id = "p3", name = "Box à louer", amountCents = 1800000, position = 2)
        coEvery { repository.createProject(any()) } returns Result.success(created)
        val viewModel = viewModel()
        compose.setContent { ProjectFormScreen(viewModel, projectId = null, onClose = {}) }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(UiTags.PROJECT_NAME).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(UiTags.PROJECT_NAME).performTextInput("Box à louer")
        compose.onNodeWithTag(UiTags.PROJECT_AMOUNT).performTextInput("18000")

        compose.onNodeWithTag(UiTags.PROJECT_SAVE).assertTextEquals("Créer")
        compose.onNodeWithTag(UiTags.PROJECT_SAVE).performClick()

        compose.waitForIdle()
        coVerify(timeout = 5_000) { repository.createProject(any()) }
        coVerify(exactly = 0) { repository.previewProject(any()) }
    }

    @Test
    fun `the Gantt draws a line per project, each with its saving bar and its purchase mark`() {
        compose.setContent { ProjectListScreen(viewModel(), onBack = {}, onOpenForm = {}) }

        compose.waitUntil(5_000) { compose.onAllNodesWithTag(UiTags.GANTT_ROW).fetchSemanticsNodes().size == 2 }
        compose.onNodeWithTag(UiTags.PROJECT_GANTT).assertIsDisplayed()
        assertEquals(2, compose.onAllNodesWithTag(UiTags.GANTT_SAVING).fetchSemanticsNodes().size)
        assertEquals(2, compose.onAllNodesWithTag(UiTags.GANTT_PURCHASE).fetchSemanticsNodes().size)
        assertEquals(1, compose.onAllNodesWithTag(UiTags.GANTT_FIXED, useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test
    fun `a tap on a bar says the month and the amounts`() {
        compose.setContent { ProjectListScreen(viewModel(), onBack = {}, onOpenForm = {}) }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(UiTags.GANTT_SAVING).fetchSemanticsNodes().size == 2 }

        compose.onAllNodesWithTag(UiTags.GANTT_SAVING)[0].performClick()

        compose.onNodeWithTag(UiTags.GANTT_DETAIL).assertIsDisplayed()
        compose.onNodeWithText("Déménagement · épargne de oct. 2026 à mars 2027", substring = true).assertIsDisplayed()
    }

    // « Mariage 6 000 € à date fixe M2, capacité 500 €/mois »: 2 000 € of salary in months 0 to 2, 800 € of savings in month 4.
    private val funded = planning.copy(
        months = List(36) { index ->
            val total = 2026 * 12 + 9 + index
            PlanningMonth(
                month = "${total / 12}-${(total % 12 + 1).toString().padStart(2, '0')}",
                capacityCents = 50000,
                salaryCents = if (index <= 2) 200000 else 0,
                savingsCents = if (index == 4) 80000 else 0,
                isOverCapacity = index <= 2,
            )
        },
    )

    @Test
    fun `the funding chart sits under the Gantt, a bar per month, the three first flagged`() {
        compose.setContent { ProjectListScreen(viewModel(plan = Result.success(funded)), onBack = {}, onOpenForm = {}) }

        compose.waitUntil(5_000) { compose.onAllNodesWithTag(UiTags.FUNDING_MONTH).fetchSemanticsNodes().size == 36 }
        compose.onNodeWithTag(UiTags.FUNDING_CHART).assertIsDisplayed()
        assertEquals(3, compose.onAllNodesWithTag(UiTags.FUNDING_SALARY, useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(1, compose.onAllNodesWithTag(UiTags.FUNDING_SAVINGS, useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(3, compose.onAllNodesWithTag(UiTags.FUNDING_OVER, useUnmergedTree = true).fetchSemanticsNodes().size)
        compose.onNodeWithTag(UiTags.FUNDING_LEGEND).assertIsDisplayed()
        compose.onNodeWithText("Capital libéré").assertIsDisplayed()
    }

    @Test
    fun `a tap on a flagged month says the amount by source, the capacity and the excess`() {
        compose.setContent { ProjectListScreen(viewModel(plan = Result.success(funded)), onBack = {}, onOpenForm = {}) }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(UiTags.FUNDING_MONTH).fetchSemanticsNodes().size == 36 }

        compose.onAllNodesWithTag(UiTags.FUNDING_MONTH)[0].performClick()

        compose.onNodeWithTag(UiTags.FUNDING_DETAIL).assertIsDisplayed()
        compose.onNodeWithText("oct. 2026", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Dépassement", substring = true).assertIsDisplayed()
    }

    @Test
    fun `without months in the planning the Gantt stands alone`() {
        compose.setContent { ProjectListScreen(viewModel(), onBack = {}, onOpenForm = {}) }

        compose.waitUntil(5_000) { compose.onAllNodesWithTag(UiTags.GANTT_ROW).fetchSemanticsNodes().size == 2 }
        compose.waitForIdle()

        assertEquals(0, compose.onAllNodesWithTag(UiTags.FUNDING_CHART).fetchSemanticsNodes().size)
    }

    @Test
    fun `without a known income there is no Gantt and the list stays`() {
        val unknown = planning.copy(isIncomeKnown = false)
        compose.setContent { ProjectListScreen(viewModel(plan = Result.success(unknown)), onBack = {}, onOpenForm = {}) }

        compose.waitUntil(5_000) { compose.onAllNodesWithTag(UiTags.PROJECT_ROW).fetchSemanticsNodes().size == 2 }
        compose.waitForIdle()

        assertEquals(0, compose.onAllNodesWithTag(UiTags.PROJECT_GANTT).fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithTag(UiTags.GANTT_ERROR).fetchSemanticsNodes().size)
    }

    @Test
    fun `a planning that cannot be read says so beside the list, and retries`() {
        val viewModel = viewModel(plan = Result.failure(RuntimeException("")))
        compose.setContent { ProjectListScreen(viewModel, onBack = {}, onOpenForm = {}) }

        compose.waitUntil(5_000) { compose.onAllNodesWithTag(UiTags.GANTT_ERROR).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Le planning n'a pas pu être chargé.").assertIsDisplayed()
        compose.onAllNodesWithTag(UiTags.PROJECT_ROW).fetchSemanticsNodes().size.let { assertEquals(2, it) }

        coEvery { repository.getProjectPlanning() } returns Result.success(planning)
        compose.onNodeWithText("Réessayer").performClick()

        compose.waitUntil(5_000) { compose.onAllNodesWithTag(UiTags.GANTT_ROW).fetchSemanticsNodes().size == 2 }
    }

    @Test
    fun `a tap on a row opens its sheet, the button a new form, and a funded row names its sources`() {
        val funded = sofa.copy(
            fundingSources = listOf(
                ProjectFundingSource(kind = "savings", amountCents = 10_000, accountId = "a1"),
                ProjectFundingSource(kind = "loan", amountCents = 50_000, loanId = "l1"),
            ),
            salaryCents = 60_000,
        )
        val forms = mutableListOf<String?>()
        val sheets = mutableListOf<String>()
        compose.setContent {
            ProjectListScreen(viewModel(listOf(move, funded)), onBack = {}, onOpenForm = { forms += it }, onOpenSheet = { sheets += it })
        }

        compose.waitUntil(5_000) { compose.onAllNodesWithTag(UiTags.PROJECT_ROW).fetchSemanticsNodes().size == 2 }
        compose.onAllNodesWithTag(UiTags.PROJECT_FUNDING_SUMMARY, useUnmergedTree = true).assertCountEquals(1)
        compose.onNodeWithText("économies + emprunt + salaire").assertIsDisplayed()
        compose.onNodeWithText("2. Canapé").performClick()
        compose.onNodeWithTag(UiTags.PROJECT_ADD).performClick()

        assertEquals(listOf("p2"), sheets)
        assertEquals(listOf<String?>(null), forms)
    }

    private val box = Project(
        id = "p9", name = "Box à louer", amountCents = 1_800_000, wellbeing = "comfort", wellbeingScore = 1, position = 0,
        investment = ProjectInvestment(
            projectId = "p9", priceCents = 1_800_000, downPaymentCents = 300_000, loanId = "l1", loanPaymentCents = 14_108,
            monthlyRentCents = 16_000, monthlyBalanceCents = 1_892,
            resale = ProjectResale(month = "2028-06", netAmountCents = 1_000_000, targetProjectId = "p2"),
        ),
    )

    @Test
    fun `an investment row shows the rent, the loan payment, the balance and the planned resale (MAG-557)`() {
        compose.setContent { ProjectListScreen(viewModel(listOf(box, sofa.copy(position = 1))), onBack = {}, onOpenForm = {}) }

        compose.waitUntil(5_000) { compose.onAllNodesWithTag(UiTags.PROJECT_ROW).fetchSemanticsNodes().size == 2 }
        compose.onAllNodesWithTag(UiTags.PROJECT_ROW_INVESTMENT, useUnmergedTree = true).assertCountEquals(1)
        compose.onNodeWithText("Loyer perçu : ${formatCents(16_000)} par mois").assertIsDisplayed()
        compose.onNodeWithText("Mensualité du prêt : ${formatCents(14_108)} par mois").assertIsDisplayed()
        compose.onNodeWithText("Solde : +${formatCents(1_892)} par mois").assertIsDisplayed()
        compose.onNodeWithText("Revendre en juin 2028", substring = true).assertIsDisplayed()
        compose.onNodeWithText("pour l'apport de Canapé", substring = true).assertIsDisplayed()
    }

    @Test
    fun `a box that costs more than it brings shows a negative balance, and a box with no loan no payment`() {
        val costly = box.copy(investment = box.investment!!.copy(monthlyBalanceCents = -4_108, resale = null))
        val cash = box.copy(id = "p8", name = "Garage", position = 1, investment = box.investment!!.copy(loanPaymentCents = 0, monthlyBalanceCents = 16_000, resale = null))
        compose.setContent { ProjectListScreen(viewModel(listOf(costly, cash)), onBack = {}, onOpenForm = {}) }

        compose.waitUntil(5_000) { compose.onAllNodesWithTag(UiTags.PROJECT_ROW).fetchSemanticsNodes().size == 2 }
        compose.onNodeWithText("Solde : −${formatCents(4_108)} par mois").assertIsDisplayed()
        compose.onAllNodesWithText("Mensualité du prêt", substring = true).assertCountEquals(1)
        compose.onAllNodesWithText("Revendre", substring = true).assertCountEquals(0)
    }

    @Test
    fun `a plain project shows no investment lines`() {
        compose.setContent { ProjectListScreen(viewModel(), onBack = {}, onOpenForm = {}) }

        compose.waitUntil(5_000) { compose.onAllNodesWithTag(UiTags.PROJECT_ROW).fetchSemanticsNodes().size == 2 }
        compose.onAllNodesWithTag(UiTags.PROJECT_ROW_INVESTMENT, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun `the Gantt draws the rent beside the loan and the resale point of an investment`() {
        val boxPlan = planning.copy(
            projects = listOf(
                PlannedProject(
                    projectId = "p9", name = "Box à louer", position = 0, amountCents = 1_800_000, savingFromMonth = "2026-10", purchaseMonth = "2026-12",
                    loan = PlannedLoan("2027-01", "2027-07", 14_108),
                    investment = PlannedInvestment(
                        monthlyRentCents = 16_000, monthlyBalanceCents = 1_892, rentFromMonth = "2027-01", rentUntilMonth = "2027-07",
                        resaleMonth = "2027-08", resaleNetCents = 1_000_000, resaleTargetProjectId = "p2",
                    ),
                ),
                planning.projects[1].copy(resaleFromMonth = "2027-08"),
            ),
        )
        compose.setContent {
            ProjectListScreen(viewModel(listOf(box, sofa.copy(position = 1)), Result.success(boxPlan)), onBack = {}, onOpenForm = {})
        }

        compose.waitUntil(5_000) { compose.onAllNodesWithTag(UiTags.GANTT_ROW).fetchSemanticsNodes().size == 2 }
        assertEquals(1, compose.onAllNodesWithTag(UiTags.GANTT_LOAN).fetchSemanticsNodes().size)
        assertEquals(1, compose.onAllNodesWithTag(UiTags.GANTT_RENT).fetchSemanticsNodes().size)
        assertEquals(1, compose.onAllNodesWithTag(UiTags.GANTT_RESALE).fetchSemanticsNodes().size)
    }

    @Test
    fun `a tap on the rent bar or the resale point says the month and the amounts`() {
        val boxPlan = planning.copy(
            projects = listOf(
                PlannedProject(
                    projectId = "p9", name = "Box à louer", position = 0, amountCents = 1_800_000, savingFromMonth = "2026-10", purchaseMonth = "2026-12",
                    investment = PlannedInvestment(
                        monthlyRentCents = 16_000, rentFromMonth = "2027-01", rentUntilMonth = "2027-07",
                        resaleMonth = "2027-08", resaleNetCents = 1_000_000,
                    ),
                ),
            ),
        )
        compose.setContent { ProjectListScreen(viewModel(listOf(box), Result.success(boxPlan)), onBack = {}, onOpenForm = {}) }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(UiTags.GANTT_RENT).fetchSemanticsNodes().size == 1 }

        compose.onNodeWithTag(UiTags.GANTT_RENT).performClick()
        compose.onNodeWithText("Box à louer · loyer", substring = true).assertIsDisplayed()

        compose.onNodeWithTag(UiTags.GANTT_RESALE).performClick()
        compose.onNodeWithText("Box à louer · revente en août 2027", substring = true).assertIsDisplayed()
    }
}

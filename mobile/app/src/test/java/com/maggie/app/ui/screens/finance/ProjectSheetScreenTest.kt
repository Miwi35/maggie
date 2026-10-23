package com.maggie.app.ui.screens.finance

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.FundingKind
import com.maggie.app.data.model.Placement
import com.maggie.app.data.model.Project
import com.maggie.app.data.model.ProjectFundingSource
import com.maggie.app.data.model.ProjectInvestment
import com.maggie.app.data.model.ProjectResale
import com.maggie.app.data.model.ProjectOption
import com.maggie.app.data.model.ProjectOptions
import com.maggie.app.data.model.formatCents
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The sheet of a project as drawn (MAG-525): the phone reads the funding and the three options, facts only;
 * the tablet also edits. The figures come from the fixture below, never from a recorded price.
 */
@RunWith(AndroidJUnit4::class)
class ProjectSheetScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private val car = Project(
        id = "p3", name = "Voiture", amountCents = 1_000_000,
        fundingSources = listOf(
            ProjectFundingSource(kind = "savings", amountCents = 100_000, accountId = "a1"),
            ProjectFundingSource(kind = "loan", amountCents = 600_000, loanId = "l1"),
        ),
        salaryCents = 300_000,
    )

    private val options = ProjectOptions(
        projectId = "p3",
        amountCents = 1_000_000,
        loanRateBasisPoints = 400,
        loanMonths = 24,
        cash = ProjectOption(totalCostCents = 1_000_000, netCostCents = 990_000, availableInMonths = 20),
        loan = ProjectOption(totalCostCents = 1_042_200, netCostCents = 1_031_000, monthlyPaymentCents = 43_000, availableInMonths = 0),
        partial = ProjectOption(
            totalCostCents = 1_025_300, netCostCents = 1_010_000, monthlyPaymentCents = 25_000, availableInMonths = 8, nImpactMonths = 3,
        ),
    )

    private fun show(
        placements: List<Placement> = emptyList(),
        sheet: ProjectSheet? = ProjectSheet("p3", options = options, sources = car.fundingSources.map(FundingSourceDraft::of)),
        editable: Boolean = false,
        actions: ProjectSheetActions = ProjectSheetActions(),
        project: Project? = car,
        projects: List<Project> = emptyList(),
    ) = compose.setContent {
        ProjectSheetContent(project = project, sheet = sheet, accounts = emptyList(), hasLoaded = true, editable = editable, actions = actions, projects = projects, placements = placements)
    }

    private fun cell(tag: String, text: String) {
        compose.onNodeWithTag(tag).assert(hasAnyDescendant(hasText(text)))
    }

    @Test
    fun `the phone reads the sources and the three options side by side`() {
        show()

        compose.onNodeWithText("Voiture").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.PROJECT_FUNDING_SUMMARY).assert(hasText("économies + emprunt + salaire"))
        compose.onNodeWithTag(UiTags.PROJECT_FUNDING_BAR).assertIsDisplayed()
        val legend = compose.onAllNodesWithTag(UiTags.PROJECT_FUNDING_SOURCE)
        assertEquals(3, legend.fetchSemanticsNodes().size)
        legend[0].assert(hasAnyDescendant(hasText("Économies")))
        legend[0].assert(hasAnyDescendant(hasText(formatCents(100_000))))
        legend[1].assert(hasAnyDescendant(hasText("Emprunt")))
        legend[2].assert(hasAnyDescendant(hasText("Salaire")))
        legend[2].assert(hasAnyDescendant(hasText(formatCents(300_000))))

        cell(UiTags.projectOptionTotal("cash"), formatCents(1_000_000))
        cell(UiTags.projectOptionTotal("loan"), formatCents(1_042_200))
        cell(UiTags.projectOptionTotal("partial"), formatCents(1_025_300))
        cell(UiTags.projectOptionNet("loan"), formatCents(1_031_000))
        cell(UiTags.projectOptionMonthly("loan"), formatCents(43_000))
        cell(UiTags.projectOptionMonthly("cash"), "—")
        cell(UiTags.projectOptionDelay("cash"), "20 mois")
        cell(UiTags.projectOptionDelay("loan"), "Immédiat")
        cell(UiTags.projectOptionDelay("partial"), "8 mois")
    }

    @Test
    fun `the impact on N appears only for the option that has one`() {
        show()

        compose.onAllNodesWithText("Impact sur N").let { assertEquals(1, it.fetchSemanticsNodes().size) }
        compose.onNodeWithText("3 mois").assertIsDisplayed()
    }

    @Test
    fun `no option is called better than another`() {
        show()

        for (word in listOf("recommandé", "préférable", "meilleur")) {
            assertEquals(word, 0, compose.onAllNodesWithText(word, substring = true, ignoreCase = true).fetchSemanticsNodes().size)
        }
    }

    @Test
    fun `the phone does not edit the sources`() {
        show()

        assertEquals(0, compose.onAllNodesWithTag(UiTags.PROJECT_FUNDING_SAVE).fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithTag(UiTags.PROJECT_OPTIONS_APPLY).fetchSemanticsNodes().size)
    }

    @Test
    fun `options that could not be priced say why instead of a figure`() {
        show(sheet = ProjectSheet("p3", optionsError = ProjectViewModel.MESSAGE_OPTIONS_TERMS))

        compose.onNodeWithTag(UiTags.PROJECT_OPTIONS_ERROR).assertIsDisplayed()
        compose.onNodeWithText(ProjectViewModel.MESSAGE_OPTIONS_TERMS).assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithTag(UiTags.projectOption("cash")).fetchSemanticsNodes().size)
    }

    @Test
    fun `the pencil opens the project's form`() {
        var edited = 0
        show(actions = ProjectSheetActions(onEdit = { edited++ }))

        compose.onNodeWithTag(UiTags.PROJECT_SHEET_EDIT).performClick()

        assertEquals(1, edited)
    }

    @Test
    fun `the tablet edits a source with the buttons and saves the whole list`() {
        val steps = mutableListOf<Int>()
        val saved = mutableListOf<List<FundingSourceDraft>>()
        val sources = car.fundingSources.map(FundingSourceDraft::of)
        show(
            sheet = ProjectSheet("p3", options = options, sources = sources),
            editable = true,
            actions = ProjectSheetActions(
                onEditSource = { index, transform -> steps += transform(sources[index]).amountCents!! - sources[index].amountCents!! },
                onSave = { saved += it },
            ),
        )

        compose.onNodeWithText("Le reste, sur salaire").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithTag(UiTags.PROJECT_FUNDING_PLUS)[0].performScrollTo().performClick()
        compose.onAllNodesWithTag(UiTags.PROJECT_FUNDING_MINUS)[0].performScrollTo().performClick()
        compose.onNodeWithTag(UiTags.PROJECT_FUNDING_SAVE).performScrollTo().performClick()

        assertEquals(listOf(FUNDING_STEP_CENTS, -FUNDING_STEP_CENTS), steps)
        assertEquals(listOf(sources), saved)
    }

    @Test
    fun `a source can be added except a second loan, and the API's refusal is shown as written`() {
        val added = mutableListOf<FundingKind>()
        val sheet = ProjectSheet(
            "p3", options = options, sources = car.fundingSources.map(FundingSourceDraft::of),
            fundingError = "Savings account is the cushion.",
        )
        show(sheet = sheet, editable = true, actions = ProjectSheetActions(onAddSource = { added += it }))

        compose.onNodeWithTag(UiTags.projectFundingAdd("savings")).performScrollTo().performClick()
        compose.onNodeWithTag(UiTags.projectFundingAdd("capital")).performScrollTo().performClick()

        assertEquals(listOf(FundingKind.SAVINGS, FundingKind.CAPITAL), added)
        assertEquals(0, compose.onAllNodesWithTag(UiTags.projectFundingAdd("loan")).fetchSemanticsNodes().size)
        compose.onNodeWithText("Savings account is the cushion.").performScrollTo().assertIsDisplayed()
    }

    private val japan = Project(id = "p2", name = "Japon", amountCents = 300_000)

    private val box = Project(
        id = "p9", name = "Box à louer", amountCents = 1_800_000,
        fundingSources = listOf(
            ProjectFundingSource(kind = "savings", amountCents = 300_000, accountId = "a1"),
            ProjectFundingSource(kind = "loan", amountCents = 1_500_000, loanId = "l1"),
        ),
        investment = ProjectInvestment(
            projectId = "p9", priceCents = 1_800_000, downPaymentCents = 300_000, loanId = "l1", loanPaymentCents = 14_108,
            monthlyRentCents = 16_000, monthlyBalanceCents = 1_892,
            resale = ProjectResale(month = "2028-06", netAmountCents = 1_000_000, targetProjectId = "p2"),
        ),
    )

    @Test
    fun `an investment shows its price, down payment, rent, loan payment, balance and resale (MAG-557)`() {
        show(sheet = ProjectSheet("p9", sources = box.fundingSources.map(FundingSourceDraft::of)), project = box, projects = listOf(box, japan))

        compose.onNodeWithTag(UiTags.PROJECT_INVESTMENT).assertIsDisplayed()
        cell(UiTags.PROJECT_INVESTMENT_PRICE, formatCents(1_800_000))
        cell(UiTags.PROJECT_INVESTMENT_DOWN_PAYMENT, formatCents(300_000))
        cell(UiTags.PROJECT_INVESTMENT_RENT, "${formatCents(16_000)} par mois")
        cell(UiTags.PROJECT_INVESTMENT_LOAN, "${formatCents(14_108)} par mois")
        cell(UiTags.PROJECT_INVESTMENT_BALANCE, "+${formatCents(1_892)} par mois")
        compose.onNodeWithTag(UiTags.PROJECT_INVESTMENT_RESALE)
            .assert(hasText("Revendre en juin 2028 pour ${formatCents(1_000_000)} net, pour l'apport de Japon"))
    }

    @Test
    fun `an investment with no loan and no resale says so instead of showing a zero`() {
        val cash = box.copy(investment = box.investment!!.copy(loanPaymentCents = 0, resale = null))
        show(sheet = ProjectSheet("p9"), project = cash, projects = listOf(cash))

        cell(UiTags.PROJECT_INVESTMENT_LOAN, "—")
        compose.onNodeWithTag(UiTags.PROJECT_INVESTMENT_RESALE).assert(hasText("Aucune revente planifiée."))
    }

    @Test
    fun `a plain project has no investment card`() {
        show()

        assertEquals(0, compose.onAllNodesWithTag(UiTags.PROJECT_INVESTMENT).fetchSemanticsNodes().size)
    }

    @Test
    fun `the resale a project receives is a segment named after its source, and the summary says so`() {
        val funded = box.copy(
            investment = null,
            fundingSources = box.fundingSources + ProjectFundingSource(kind = "resale", amountCents = 400_000, sourceProjectId = "p9"),
        )
        show(
            sheet = ProjectSheet("p2", sources = FundingDraft.draftsOf(funded.fundingSources)),
            project = funded.copy(id = "p2", name = "Japon"),
            projects = listOf(box, japan),
        )

        compose.onNodeWithTag(UiTags.PROJECT_FUNDING_SUMMARY).assert(hasText("économies + emprunt + revente"))
        val legend = compose.onAllNodesWithTag(UiTags.PROJECT_FUNDING_SOURCE)
        assertEquals(3, legend.fetchSemanticsNodes().size)
        legend[2].assert(hasAnyDescendant(hasText("Revente de Box à louer")))
        legend[2].assert(hasAnyDescendant(hasText(formatCents(400_000))))
    }

    @Test
    fun `the tablet does not offer to add a resale, and its editor lists only the sources it can save`() {
        val funded = car.copy(
            fundingSources = car.fundingSources + ProjectFundingSource(kind = "resale", amountCents = 50_000, sourceProjectId = "p9"),
        )
        val sources = FundingDraft.draftsOf(funded.fundingSources)
        show(sheet = ProjectSheet("p3", options = options, sources = sources), editable = true, project = funded, projects = listOf(box, funded))

        assertEquals(0, compose.onAllNodesWithTag(UiTags.projectFundingAdd("resale")).fetchSemanticsNodes().size)
        compose.onNodeWithTag(UiTags.projectFundingAdd("savings")).performScrollTo().assertIsDisplayed()
        assertEquals(2, sources.size)
    }

    @Test
    fun `a capital source offers the placement it leaves, optional, and the choice is edited (MAG-556)`() {
        val sources = listOf(FundingSourceDraft(FundingKind.CAPITAL, amount = "1500", accountId = "a2", placementId = "pea"))
        val edited = mutableListOf<String?>()
        show(
            placements = listOf(Placement(id = "pea", name = "PEA"), Placement(id = "life", name = "Assurance-vie")),
            sheet = ProjectSheet("p3", options = options, sources = sources),
            editable = true,
            actions = ProjectSheetActions(onEditSource = { index, transform -> edited += transform(sources[index]).placementId }),
        )

        compose.onNodeWithText("Placement quitté (facultatif)").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(UiTags.projectFundingPlacement("life")).performScrollTo().performClick()
        compose.onNodeWithTag(UiTags.PROJECT_FUNDING_PLACEMENT_NONE).performScrollTo().performClick()

        assertEquals(listOf<String?>("life", null), edited)
    }

    @Test
    fun `a savings source does not offer a placement to leave`() {
        val savings = listOf(FundingSourceDraft(FundingKind.SAVINGS, amount = "1000", accountId = "a1"))
        show(
            placements = listOf(Placement(id = "pea", name = "PEA")),
            sheet = ProjectSheet("p3", options = options, sources = savings),
            editable = true,
        )
        assertEquals(0, compose.onAllNodesWithText("Placement quitté (facultatif)").fetchSemanticsNodes().size)
    }
}

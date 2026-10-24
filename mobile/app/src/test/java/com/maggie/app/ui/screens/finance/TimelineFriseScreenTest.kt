package com.maggie.app.ui.screens.finance

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.api.ApiRefusalException
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.model.PlannedProject
import com.maggie.app.data.model.Project
import com.maggie.app.data.model.ProjectPlanning
import com.maggie.app.data.repository.ProjectRepository
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The planning card's « 36 mois · 20 ans » switch and the 20-year frise (MAG-554): what the screen
 * draws from the API's answer — the Gantt by default, the frise on request, the date N on the
 * « Indépendance » event, the second scenario under the first, the assumptions, the API's own sentence.
 */
@RunWith(AndroidJUnit4::class)
class TimelineFriseScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private val repository = mockk<ProjectRepository>()

    private val move = Project(id = "p1", name = "Déménagement", amountCents = 250000, wellbeing = "essential", wellbeingScore = 4, position = 0)

    private val planning = ProjectPlanning(
        startMonth = "2026-10",
        isIncomeKnown = true,
        projects = listOf(
            PlannedProject(projectId = "p1", name = "Déménagement", position = 0, amountCents = 250000, savingFromMonth = "2026-10", purchaseMonth = "2027-03"),
        ),
    )

    private fun viewModels(): Pair<ProjectViewModel, FinanceTimelineViewModel> {
        coEvery { repository.getProjects() } returns Result.success(listOf(move))
        coEvery { repository.getProjectPlanning() } returns Result.success(planning)
        coEvery { repository.getTimeline(null) } returns Result.success(TimelineFixtures.alone())
        coEvery { repository.getTimeline("auto-quality") } returns Result.success(TimelineFixtures.compared())
        val auth = mockk<AuthRepository>()
        coEvery { auth.getUserId() } returns "user-1"
        val mercure = mockk<MercureService>()
        every { mercure.subscribe(any()) } returns emptyFlow()
        return ProjectViewModel(repository, mercure, auth) to FinanceTimelineViewModel(repository)
    }

    private fun show(configure: () -> Unit = {}) {
        val (projects, timeline) = viewModels()
        configure()
        compose.setContent { ProjectListScreen(projects, onBack = {}, onOpenForm = {}, timelineViewModel = timeline) }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(UiTags.PROJECT_GANTT).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun open20Years() {
        compose.onNodeWithTag(UiTags.timelineHorizon(240)).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(UiTags.TIMELINE_ASSUMPTIONS).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun `the card opens on 36 mois with the Gantt, as before`() {
        show()

        compose.onNodeWithTag(UiTags.timelineHorizon(36)).assertIsSelected()
        compose.onAllNodesWithTag(UiTags.TIMELINE_FRISE).assertCountEquals(0)
    }

    @Test
    fun `20 ans draws the frise with the independence event and the assumptions, in place of the Gantt`() {
        show()
        open20Years()

        compose.onNodeWithTag(UiTags.timelineHorizon(240)).assertIsSelected()
        compose.onAllNodesWithTag(UiTags.PROJECT_GANTT).assertCountEquals(0)
        compose.onNodeWithTag(UiTags.timelineEvent("independence_reached")).assertTextContains("Indépendance financière", substring = true)
        compose.onNodeWithTag(UiTags.timelineEvent("independence_reached")).assertTextContains("décembre 2045", substring = true)
        compose.onNodeWithTag(UiTags.TIMELINE_N_MARKER).assertExists()
        compose.onNodeWithTag(UiTags.TIMELINE_ASSUMPTIONS)
            .assertTextEquals("Hypothèses : matelas intact, revenus stables, sans vacance locative ni imprévu")
        compose.onAllNodesWithTag(UiTags.TIMELINE_DIFFERENCE).assertCountEquals(0)
    }

    @Test
    fun `choosing a scenario draws it under the followed one, with the gap in the API's words`() {
        show()
        open20Years()

        compose.onNodeWithTag(UiTags.timelineCompare("auto-quality")).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(UiTags.TIMELINE_DIFFERENCE).fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithTag(UiTags.TIMELINE_DIFFERENCE).assertTextEquals("N 2 ans 3 mois plus tôt que « Mon plan »")
        compose.onNodeWithTag(UiTags.timelineEvent("independence_reached", compared = true)).assertTextContains("septembre 2043", substring = true)
        compose.onNodeWithTag(UiTags.TIMELINE_COMPARED_TRACK).assertExists()
    }

    @Test
    fun `a comparison the API refuses says why and draws the followed scenario alone`() {
        show { coEvery { repository.getTimeline("auto-rapid") } returns Result.failure(ApiRefusalException(400, "Compare it with another one.")) }
        open20Years()

        compose.onNodeWithTag(UiTags.timelineCompare("auto-rapid")).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(UiTags.TIMELINE_NOTICE).fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithTag(UiTags.TIMELINE_NOTICE).assertTextEquals("Compare it with another one.")
        compose.onAllNodesWithTag(UiTags.TIMELINE_COMPARED_TRACK).assertCountEquals(0)
    }

    @Test
    fun `a frise that cannot be read says so and Réessayer reads it again`() {
        show { coEvery { repository.getTimeline(null) } returns Result.failure(RuntimeException("offline")) }

        compose.onNodeWithTag(UiTags.timelineHorizon(240)).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(UiTags.TIMELINE_ERROR).fetchSemanticsNodes().isNotEmpty() }

        coEvery { repository.getTimeline(null) } returns Result.success(TimelineFixtures.alone())
        compose.onNodeWithText("Réessayer").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(UiTags.TIMELINE_ASSUMPTIONS).fetchSemanticsNodes().isNotEmpty() }

        compose.onAllNodesWithTag(UiTags.TIMELINE_ERROR).assertCountEquals(0)
    }

    @Test
    fun `back to 36 mois brings the Gantt back`() {
        show()
        open20Years()

        compose.onNodeWithTag(UiTags.timelineHorizon(36)).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(UiTags.PROJECT_GANTT).fetchSemanticsNodes().isNotEmpty() }

        compose.onAllNodesWithTag(UiTags.TIMELINE_FRISE).assertCountEquals(0)
    }
}

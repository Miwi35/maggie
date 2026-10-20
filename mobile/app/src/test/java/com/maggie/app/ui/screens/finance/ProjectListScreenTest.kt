package com.maggie.app.ui.screens.finance

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.filter
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.model.Project
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

    private fun viewModel(projects: List<Project> = listOf(move, sofa)): ProjectViewModel {
        coEvery { repository.getProjects() } returns Result.success(projects)
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
}

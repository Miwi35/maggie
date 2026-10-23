package com.maggie.app.ui.screens.finance

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.CoveragePoint
import com.maggie.app.data.model.Scenario
import com.maggie.app.data.model.ScenarioComparison
import com.maggie.app.data.model.ScenarioDifference
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The scenarios as drawn (MAG-551): no current scenario by default and a way to pick one; « Rendre courant »,
 * « Dupliquer »; a suggestion to see or ignore; the comparison as figures and the API's own sentence, with
 * no verdict. The sheet's window and the editor are the journey's (`26-finance-scenarios`).
 */
@RunWith(AndroidJUnit4::class)
class ScenarioPanelTest {

    @get:Rule
    val compose = ScreenRule()

    private val rapid = Scenario(id = "auto-rapid", name = "Rapide", kind = "auto", nDate = "2039-05")
    private val balanced = Scenario(id = "auto-balanced", name = "Équilibré", kind = "auto", nDate = "2041-03")
    private val mine = Scenario(id = "s1", name = "Mon plan", kind = "user", status = "saved")
    private val advice = Scenario(id = "adv1", name = "Idée", kind = "advisor")

    private val calls = mutableListOf<String>()
    private val actions = ScenarioActions(
        onMakeCurrent = { calls += "current:$it" },
        onClearCurrent = { calls += "clear" },
        onDuplicate = { calls += "duplicate:$it" },
        onToggleCompared = { calls += "compare:$it" },
        onClearComparison = { calls += "clear-comparison" },
        onAdd = { calls += "add" },
        onEdit = { calls += "edit:$it" },
        onDelete = { calls += "delete:$it" },
        onDismiss = { calls += "dismiss:$it" },
        onRetry = { calls += "retry" },
    )

    private fun state(vararg scenarios: Scenario, selected: List<String> = emptyList(), comparison: ScenarioComparison? = null) =
        ScenarioUiState(scenarios = scenarios.toList(), hasLoaded = true, selected = selected, comparison = comparison)

    @Test
    fun `without a current scenario the card says so and invites to pick one`() {
        compose.setContent { ScenarioCurrentCard(state(rapid, balanced), onClick = { calls += "open" }) }

        compose.onNodeWithText("Aucun scénario courant").assertIsDisplayed()
        compose.onNodeWithText("Choisir un scénario").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.SCENARIO_CURRENT_CARD).performClick()
        assertEquals(listOf("open"), calls)
    }

    @Test
    fun `the card names the current scenario and its date N`() {
        compose.setContent { ScenarioCurrentCard(state(rapid, balanced.copy(current = true)), onClick = {}) }

        compose.onNodeWithText("Scénario courant · Équilibré").assertIsDisplayed()
        compose.onNodeWithText("N : mars 2041").assertIsDisplayed()
    }

    @Test
    fun `the panel offers to pick a scenario when none is current, and acts on a tap`() {
        compose.setContent { ScenarioPanel(state(rapid, balanced, mine), actions, manage = false) }

        compose.onNodeWithTag(UiTags.SCENARIO_NONE).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.scenarioMakeCurrent("auto-rapid")).performClick()
        compose.onNodeWithTag(UiTags.scenarioDuplicate("auto-rapid")).performClick()
        compose.onNodeWithTag(UiTags.SCENARIO_ADD).performClick()

        assertEquals(listOf("current:auto-rapid", "duplicate:auto-rapid", "add"), calls)
    }

    @Test
    fun `the current scenario wears its pill and has no make-current button`() {
        compose.setContent { ScenarioPanel(state(rapid, balanced.copy(current = true)), actions, manage = false) }

        compose.onNodeWithTag(UiTags.scenarioBadge("auto-balanced")).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.scenarioMakeCurrent("auto-rapid")).assertIsDisplayed()
        assertEquals(0, compose.onAllNodesCount(UiTags.scenarioMakeCurrent("auto-balanced")))
    }

    @Test
    fun `the gap of N is shown as the API worded it`() {
        val panel = state(rapid, balanced.copy(current = true)).copy(gaps = mapOf("auto-rapid" to "N 22 mois plus tôt que « Équilibré »"))
        compose.setContent { ScenarioPanel(panel, actions, manage = false) }

        compose.onNodeWithText("N 22 mois plus tôt que « Équilibré »").assertIsDisplayed()
    }

    @Test
    fun `a suggestion of the advisor is seen or ignored, never deleted by name`() {
        compose.setContent { ScenarioPanel(state(rapid, advice), actions, manage = true) }

        compose.onNodeWithText("Suggestion du conseiller · brouillon").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.scenarioEdit("adv1")).performClick()
        compose.onNodeWithTag(UiTags.scenarioDismiss("adv1")).performClick()

        assertEquals(listOf("edit:adv1", "dismiss:adv1"), calls)
        assertEquals(0, compose.onAllNodesCount(UiTags.scenarioDelete("adv1")))
    }

    @Test
    fun `an automatic scenario can be neither edited nor deleted, one of the owner can`() {
        compose.setContent { ScenarioPanel(state(rapid, mine), actions, manage = true) }

        assertEquals(0, compose.onAllNodesCount(UiTags.scenarioEdit("auto-rapid")))
        assertEquals(0, compose.onAllNodesCount(UiTags.scenarioDelete("auto-rapid")))
        compose.onNodeWithTag(UiTags.scenarioEdit("s1")).performClick()
        compose.onNodeWithTag(UiTags.scenarioDelete("s1")).performClick()
        compose.onNodeWithTag(UiTags.SCENARIO_DELETE_CONFIRM).performClick()

        assertEquals(listOf("edit:s1", "delete:s1"), calls)
    }

    @Test
    fun `the phone offers neither editing nor deleting in the list`() {
        compose.setContent { ScenarioPanel(state(rapid, mine), actions, manage = false) }

        assertEquals(0, compose.onAllNodesCount(UiTags.scenarioEdit("s1")))
        assertEquals(0, compose.onAllNodesCount(UiTags.scenarioDelete("s1")))
    }

    @Test
    fun `ticking a scenario asks for the comparison`() {
        compose.setContent { ScenarioPanel(state(rapid, balanced), actions, manage = false) }

        compose.onNodeWithTag(UiTags.scenarioCompare("auto-rapid")).performClick()

        assertEquals(listOf("compare:auto-rapid"), calls)
    }

    @Test
    fun `the comparison puts figures side by side and the API's sentence, with no verdict`() {
        val a = rapid.copy(curve = listOf(CoveragePoint("2026-10", 0.2), CoveragePoint("2039-05", 1.0)))
        val b = balanced.copy(curve = listOf(CoveragePoint("2026-10", 0.2), CoveragePoint("2041-03", 1.0)))
        val comparison = ScenarioComparison(
            referenceId = "auto-balanced",
            scenarios = listOf(b, a),
            differences = listOf(ScenarioDifference("auto-rapid", "auto-balanced", months = -22, sentence = "N 22 mois plus tôt que « Équilibré »")),
        )
        compose.setContent {
            ScenarioPanel(state(rapid, balanced, selected = listOf("auto-balanced", "auto-rapid"), comparison = comparison), actions, manage = false)
        }

        compose.onNodeWithTag(UiTags.SCENARIO_CURVES).assertExists()
        compose.onNodeWithTag(UiTags.SCENARIO_TABLE).assertExists()
        compose.onNodeWithText("Date N").assertExists()
        compose.onNodeWithText("Rapide · N 22 mois plus tôt que « Équilibré »").assertExists()
        assertEquals(0, compose.onAllNodesWithTextCount("recommandé"))
        assertEquals(0, compose.onAllNodesWithTextCount("meilleur"))
    }

    @Test
    fun `one ticked scenario asks for a second`() {
        compose.setContent { ScenarioPanel(state(rapid, balanced, selected = listOf("auto-rapid")), actions, manage = false) }
        compose.onNodeWithText("Cochez un second scénario.").assertIsDisplayed()
    }

    @Test
    fun `a failed first load offers a retry`() {
        compose.setContent { ScenarioPanel(ScenarioUiState(error = "Les scénarios n'ont pas pu être chargés."), actions, manage = false) }

        compose.onNodeWithText("Réessayer").performClick()

        assertEquals(listOf("retry"), calls)
    }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.onAllNodesCount(tag: String): Int =
        onAllNodesWithTag(tag).fetchSemanticsNodes().size

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.onAllNodesWithTextCount(text: String): Int =
        onAllNodesWithText(text, substring = true, ignoreCase = true).fetchSemanticsNodes().size
}

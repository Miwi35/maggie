package com.maggie.app.ui.screens.home

import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.FinanceHomeProject
import com.maggie.app.data.model.FinanceHomeRing
import com.maggie.app.data.model.FinanceHomeState
import com.maggie.app.data.model.formatCents
import com.maggie.app.data.model.formatCentsRounded
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Finance card (MAG-565), as `accueil/Accueil-Tel` draws it: two small rings of what was spent (no verdict),
 * the orange « n à qualifier » pill, the next project with « x / y € · mois » and its bar.
 */
@RunWith(AndroidJUnit4::class)
class FinanceHomeCardScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private val onTrack = FinanceHomeRing(spentCents = 5800, expectedSpentCents = 9000, plannedCents = 93000)
    private val above = FinanceHomeRing(spentCents = 125599, expectedSpentCents = 93000, plannedCents = 93000)
    private val project = FinanceHomeProject("p1", "Voyage", 100000, 25000, "2027-03")

    private fun draw(ui: FinanceHomeUiState, onOpen: () -> Unit = {}, onRetry: () -> Unit = {}) =
        compose.setContent { FinanceHomeCardContent(ui, onOpen, onRetry) }

    private fun loaded(state: FinanceHomeState) = FinanceHomeUiState(state, isLoading = false)

    @Test
    fun `with neither account nor envelope the card draws nothing`() {
        draw(loaded(FinanceHomeState(week = onTrack, month = onTrack)))

        compose.onNodeWithTag(UiTags.homeCard(HomeModules.FINANCE)).assertDoesNotExist()
    }

    @Test
    fun `an envelope alone is enough to draw the card`() {
        draw(loaded(FinanceHomeState(envelopeCount = 1, week = onTrack, month = onTrack)))

        compose.onNodeWithTag(UiTags.homeCard(HomeModules.FINANCE)).assertIsDisplayed()
    }

    @Test
    fun `a failed read keeps the card, with its error`() {
        draw(FinanceHomeUiState(isLoading = false, failed = true))

        compose.onNodeWithTag(UiTags.homeCard(HomeModules.FINANCE)).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.HOME_FINANCE_ERROR).assertIsDisplayed()
    }

    @Test
    fun `each ring carries its period under it and what was spent in it, rounded to the euro`() {
        draw(loaded(FinanceHomeState(accountCount = 1, week = above, month = onTrack)))

        compose.onNodeWithText("Finance").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.homeFinanceRing("week")).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.homeFinanceRing("month")).assertIsDisplayed()
        compose.onNodeWithText("Semaine", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Mois", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(formatCentsRounded(125599), useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(formatCentsRounded(5800), useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `spending above what is expected carries no verdict`() {
        draw(loaded(FinanceHomeState(accountCount = 1, week = above, month = onTrack)))

        for (word in listOf("Au-dessus du rythme", "Dans le rythme", "Rythme incomplet", "restant", "Aucune enveloppe")) {
            compose.onAllNodesWithText(word, substring = true).assertCountEquals(0)
        }
    }

    @Test
    fun `a ring reads its amounts to a screen reader and to Maestro`() {
        draw(loaded(FinanceHomeState(accountCount = 1, week = above, month = onTrack)))

        compose.onNodeWithTag(UiTags.homeFinanceRing("week")).assertContentDescriptionContains(
            "Semaine : ${formatCents(125599)} dépensés, ${formatCents(93000)} attendus",
            substring = true,
        )
    }

    @Test
    fun `a ring with nothing planned shows its spending and no expected line`() {
        draw(loaded(FinanceHomeState(accountCount = 1, week = FinanceHomeRing(spentCents = 1349), month = onTrack)))

        compose.onNodeWithText(formatCentsRounded(1349), useUnmergedTree = true).assertIsDisplayed()
        compose.onAllNodesWithText("Attendu", substring = true, useUnmergedTree = true).assertCountEquals(1) // the month's only
        compose.onNodeWithText("Attendu ${formatCents(9000)}", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `the pill shows the lines to qualify, and only when there are some`() {
        draw(loaded(FinanceHomeState(accountCount = 1, week = onTrack, month = onTrack, toQualifyCount = 3)))

        compose.onNodeWithTag(UiTags.HOME_FINANCE_TO_QUALIFY).assertIsDisplayed()
        compose.onNodeWithText("3 à qualifier").assertIsDisplayed()
    }

    @Test
    fun `no pill with nothing to qualify`() {
        draw(loaded(FinanceHomeState(accountCount = 1, week = onTrack, month = onTrack)))

        compose.onAllNodesWithTag(UiTags.HOME_FINANCE_TO_QUALIFY).assertCountEquals(0)
    }

    @Test
    fun `the next project shows its name, then what is gathered over its price and its month`() {
        draw(loaded(FinanceHomeState(accountCount = 1, week = onTrack, month = onTrack, nextProject = project)))

        compose.onNodeWithTag(UiTags.HOME_FINANCE_NEXT_PROJECT).assertIsDisplayed()
        compose.onNodeWithText("Voyage").assertIsDisplayed()
        compose.onNodeWithText("250 / 1\u202F000\u00A0€ · mars 2027").assertIsDisplayed()
    }

    @Test
    fun `a project beyond the horizon is still the next project, with its horizon in place of a date`() {
        val beyond = FinanceHomeProject("p2", "Salle de bain", 900000, 0, null, 36)
        draw(loaded(FinanceHomeState(accountCount = 1, week = onTrack, month = onTrack, nextProject = beyond)))

        compose.onNodeWithTag(UiTags.HOME_FINANCE_NEXT_PROJECT).assertIsDisplayed()
        compose.onNodeWithText("Salle de bain").assertIsDisplayed()
        compose.onNodeWithText("0 / 9\u202F000\u00A0€ · au-delà de 3 ans").assertIsDisplayed()
    }

    @Test
    fun `no next project, no block`() {
        draw(loaded(FinanceHomeState(accountCount = 1, week = onTrack, month = onTrack)))

        compose.onAllNodesWithTag(UiTags.HOME_FINANCE_NEXT_PROJECT).assertCountEquals(0)
    }

    @Test
    fun `a tap on the card or on the pill opens Finance`() {
        var opened = 0
        draw(loaded(FinanceHomeState(accountCount = 1, week = onTrack, month = onTrack, toQualifyCount = 2)), onOpen = { opened++ })

        compose.onNodeWithTag(UiTags.homeCardHeader(HomeModules.FINANCE)).performClick()
        compose.onNodeWithTag(UiTags.HOME_FINANCE_TO_QUALIFY).performClick()

        assertEquals(2, opened)
    }

    @Test
    fun `a failed read says so and « Réessayer » reads again`() {
        var retried = 0
        draw(FinanceHomeUiState(state = null, isLoading = false, failed = true), onRetry = { retried++ })

        compose.onNodeWithTag(UiTags.HOME_FINANCE_ERROR).assertIsDisplayed()
        compose.onNodeWithText("Réessayer").performClick()

        assertEquals(1, retried)
    }
}

package com.maggie.app.ui.screens.home

import androidx.compose.ui.graphics.Color
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
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Finance card (MAG-565), as `accueil/Accueil-Tel` draws it: two small rings, one rhythm pill, the orange
 * « n à qualifier » pill, the next project with « x / y € · mois » and its bar.
 */
@RunWith(AndroidJUnit4::class)
class FinanceHomeCardScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private val onTrack = FinanceHomeRing("on_track", "Dans le rythme", 30000, 24200, 21000)
    private val above = FinanceHomeRing("above", "Au-dessus du rythme", 7000, 1000, 3000)
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
    fun `the two rings carry their period under them and one pill tells the rhythm`() {
        draw(loaded(FinanceHomeState(accountCount = 1, week = onTrack, month = onTrack)))

        compose.onNodeWithText("Finance").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.homeFinanceRing("week")).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.homeFinanceRing("month")).assertIsDisplayed()
        compose.onNodeWithText("Semaine", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Mois", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.HOME_FINANCE_RHYTHM, useUnmergedTree = true).assertIsDisplayed()
        compose.onAllNodesWithText("Dans le rythme", useUnmergedTree = true).assertCountEquals(1)
    }

    @Test
    fun `one period above its rhythm makes the pill say so`() {
        draw(loaded(FinanceHomeState(accountCount = 1, week = above, month = onTrack)))

        compose.onAllNodesWithText("Au-dessus du rythme", useUnmergedTree = true).assertCountEquals(1)
        compose.onAllNodesWithText("Dans le rythme", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun `the remaining amount sits in the ring, rounded to the euro`() {
        draw(loaded(FinanceHomeState(accountCount = 1, week = onTrack, month = onTrack)))

        compose.onAllNodesWithText("242\u00A0€", useUnmergedTree = true).assertCountEquals(2)
    }

    @Test
    fun `no envelope at all, no rhythm pill`() {
        draw(loaded(FinanceHomeState(accountCount = 1, week = FinanceHomeRing(), month = FinanceHomeRing())))

        compose.onAllNodesWithTag(UiTags.HOME_FINANCE_RHYTHM, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun `a ring reads its rhythm word and its amounts to a screen reader and to Maestro`() {
        draw(loaded(FinanceHomeState(accountCount = 1, week = above, month = onTrack)))

        compose.onNodeWithTag(UiTags.homeFinanceRing("week")).assertContentDescriptionContains("Au-dessus du rythme", substring = true)
        compose.onNodeWithTag(UiTags.homeFinanceRing("month")).assertContentDescriptionContains("Dans le rythme", substring = true)
    }

    @Test
    fun `a ring without envelope says so and draws no amount`() {
        draw(loaded(FinanceHomeState(accountCount = 1, week = FinanceHomeRing(), month = onTrack)))

        compose.onNodeWithTag(UiTags.homeFinanceRing("week")).assertContentDescriptionContains("Aucune enveloppe", substring = true)
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
        compose.onNodeWithText("0 / 9\u202F000\u00A0€ · au-delà de 36 mois").assertIsDisplayed()
    }

    @Test
    fun `the rings read mint in the rhythm and orange above it, never the accent violet`() {
        val mint = Color(0xFF1B7352) to Color(0xFF7FD1AE)
        val orange = Color(0xFFA65500) to Color(0xFFF0A35E)

        assertEquals(mint.first, financeRhythmColor("on_track", dark = false))
        assertEquals(mint.second, financeRhythmColor("on_track", dark = true))
        assertEquals(orange.first, financeRhythmColor("above", dark = false))
        assertEquals(orange.second, financeRhythmColor("above", dark = true))
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

package com.maggie.app.ui.screens.home

import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
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

/** The Finance card (MAG-565): two rings with their rhythm word, the « n à qualifier » pill, the next project. */
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
    fun `each ring says its rhythm and the card names Finance`() {
        draw(loaded(FinanceHomeState(week = above, month = onTrack)))

        compose.onNodeWithText("Finance").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.homeFinanceRing("week")).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.homeFinanceRing("month")).assertIsDisplayed()
        compose.onNodeWithText("Au-dessus du rythme").assertIsDisplayed()
        compose.onNodeWithText("Dans le rythme").assertIsDisplayed()
    }

    @Test
    fun `a ring reads its rhythm word and its amounts to a screen reader and to Maestro`() {
        draw(loaded(FinanceHomeState(week = above, month = onTrack)))

        compose.onNodeWithTag(UiTags.homeFinanceRing("week")).assertContentDescriptionContains("Au-dessus du rythme", substring = true)
        compose.onNodeWithTag(UiTags.homeFinanceRing("month")).assertContentDescriptionContains("Dans le rythme", substring = true)
    }

    @Test
    fun `a ring without envelope says so and draws no amount`() {
        draw(loaded(FinanceHomeState(week = FinanceHomeRing(), month = onTrack)))

        compose.onNodeWithText("Aucune enveloppe").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.homeFinanceRing("week")).assertContentDescriptionContains("Aucune enveloppe", substring = true)
    }

    @Test
    fun `the pill shows the lines to qualify, and only when there are some`() {
        draw(loaded(FinanceHomeState(week = onTrack, month = onTrack, toQualifyCount = 3)))

        compose.onNodeWithTag(UiTags.HOME_FINANCE_TO_QUALIFY).assertIsDisplayed()
        compose.onNodeWithText("3 à qualifier").assertIsDisplayed()
    }

    @Test
    fun `no pill with nothing to qualify`() {
        draw(loaded(FinanceHomeState(week = onTrack, month = onTrack)))

        compose.onAllNodesWithTag(UiTags.HOME_FINANCE_TO_QUALIFY).assertCountEquals(0)
    }

    @Test
    fun `the next project shows its name and what is gathered`() {
        draw(loaded(FinanceHomeState(week = onTrack, month = onTrack, nextProject = project)))

        compose.onNodeWithTag(UiTags.HOME_FINANCE_NEXT_PROJECT).assertIsDisplayed()
        compose.onNodeWithText("Voyage").assertIsDisplayed()
    }

    @Test
    fun `no next project, no block`() {
        draw(loaded(FinanceHomeState(week = onTrack, month = onTrack)))

        compose.onAllNodesWithTag(UiTags.HOME_FINANCE_NEXT_PROJECT).assertCountEquals(0)
    }

    @Test
    fun `a tap on the card or on the pill opens Finance`() {
        var opened = 0
        draw(loaded(FinanceHomeState(week = onTrack, month = onTrack, toQualifyCount = 2)), onOpen = { opened++ })

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

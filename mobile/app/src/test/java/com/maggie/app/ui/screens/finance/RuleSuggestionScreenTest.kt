package com.maggie.app.ui.screens.finance

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.screentest.FakeRuleSuggestions
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The yes of a suggestion card, and the tag `09-finance-banks` addresses it by
 * (MAG-45).
 *
 * The journey asserted « the button is there and disabled » by its label, and it
 * failed on a build where nothing was wrong: a `Button`'s text is a node of its
 * own in the hierarchy Maestro reads, and a `Text` is always `enabled: true`, so
 * the pair « label + disabled » matches nothing — greyed out or not. The tag
 * below is what carries the state, and that is what this test pins: the tag is
 * on the button, and the button follows the heading.
 *
 * `RuleSuggestionViewModelTest` beside this one already says when `canAccept` is
 * true. What it cannot say is that the flag reaches a drawn button, which is the
 * half the emulator was paying for.
 */
@RunWith(AndroidJUnit4::class)
class RuleSuggestionScreenTest {

    @get:Rule
    val compose = ScreenRule()

    @Test
    fun `the yes is refused while the heading is a question, and offered once it is answered`() {
        val server = FakeRuleSuggestions()
        compose.setContent {
            RuleSuggestionListScreen(viewModel = server.viewModel, onBack = {})
        }

        compose.onNodeWithText("LECLERC RENNES").assertIsDisplayed()
        compose.onNodeWithText("Catégorie à choisir").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.SUGGESTION_ACCEPT).assertIsNotEnabled()

        compose.onNodeWithText("Courses").performClick()

        compose.onNodeWithTag(UiTags.SUGGESTION_ACCEPT).assertIsEnabled()
    }

    /**
     * The whole card, end to end: the heading chosen is the one written, and the
     * merchant stops being a question — asserted on the empty state, as the
     * journey does, and not on the snackbar it would race.
     */
    @Test
    fun `accepting writes the rule under the chosen heading and clears the card`() {
        val server = FakeRuleSuggestions()
        compose.setContent {
            RuleSuggestionListScreen(viewModel = server.viewModel, onBack = {})
        }

        compose.onNodeWithText("Courses").performClick()
        compose.onNodeWithTag(UiTags.SUGGESTION_ACCEPT).performClick()
        compose.waitForIdle()

        assertEquals(1, server.accepted.size)
        assertEquals("LECLERC RENNES", server.accepted.single().pattern)
        assertEquals("cat-courses", server.accepted.single().categoryId)
        assertEquals("debit", server.accepted.single().direction)
        compose.onNodeWithText("Rien à proposer pour l'instant").assertIsDisplayed()
    }
}

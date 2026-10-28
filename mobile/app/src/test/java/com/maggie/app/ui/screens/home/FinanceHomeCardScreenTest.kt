package com.maggie.app.ui.screens.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.FinanceHomeProject
import com.maggie.app.data.model.FinanceHomeRing
import com.maggie.app.data.model.FinanceHomeState
import com.maggie.app.data.model.formatCents
import com.maggie.app.data.model.formatCentsRounded
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.components.RingArcColorKey
import com.maggie.app.ui.theme.MaggieTokens
import kotlin.math.ceil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

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

    private fun arcColorOf(period: String) =
        compose.onNode(
            SemanticsMatcher.keyIsDefined(RingArcColorKey) and hasAnyAncestor(hasTestTag(UiTags.homeFinanceRing(period))),
            useUnmergedTree = true,
        ).fetchSemanticsNode().config[RingArcColorKey]

    // The phone's figures (MAG-675): 2 508 € spent this month against 1 383 € expected.
    private val monthAbove = FinanceHomeRing(spentCents = 250800, expectedSpentCents = 138300, plannedCents = 300000)

    @Test
    fun `a ring is orange above what was expected and mint in the rhythm, as on the Dashboard`() {
        draw(loaded(FinanceHomeState(accountCount = 1, week = onTrack, month = monthAbove)))

        assertEquals(MaggieTokens.Rhythm.above.first, arcColorOf("month"))
        assertEquals(MaggieTokens.Rhythm.onTrack.first, arcColorOf("week"))
    }

    // The reporting phone: 1080 × 2340 px at xxhdpi is 360 dp wide; the Accueil adds its 16 dp margin around the card.
    // Native graphics: the legacy mode measures one pixel per character, so no text would ever be cut.
    @Test
    @Config(qualifiers = "w360dp-h780dp-xxhdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `the to-qualify pill is read whole on a 360 dp phone`() {
        val week = FinanceHomeRing(spentCents = 61200, expectedSpentCents = 32100, plannedCents = 70000)
        val ui = loaded(FinanceHomeState(accountCount = 1, week = week, month = monthAbove, toQualifyCount = 365))
        compose.setContent { Box(Modifier.padding(horizontal = 16.dp)) { FinanceHomeCardContent(ui, {}, {}) } }

        val layout = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("365 à qualifier", useUnmergedTree = true).fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult].action?.invoke(layout)
        // hasVisualOverflow compares with the width the paragraph was offered, which is the whole line: it is
        // true for any text that does not fill it. Cut text is a node narrower than its natural width.
        val text = layout.single()
        val natural = ceil(text.multiParagraph.maxIntrinsicWidth).toInt()
        assertTrue("« 365 à qualifier » is clipped: ${text.size.width} px of $natural", text.size.width >= natural)
        assertEquals("« 365 à qualifier » is wrapped", 1, text.lineCount)
    }
}

package com.maggie.app.ui.screens.finance

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.FinanceDashboard
import com.maggie.app.data.model.UpcomingProjection
import com.maggie.app.data.model.UpcomingMonth
import com.maggie.app.data.model.UpcomingWeek
import com.maggie.app.data.model.formatCents
import com.maggie.app.screentest.FakeLoadedFinanceDashboard
import com.maggie.app.screentest.FakeUpcoming
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.screentest.upcomingFixture
import com.maggie.app.ui.UiTags
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * « À venir » on the finance dashboard (MAG-463): what the two sub-views show, and what the sub-view draws
 * from the projection the API sent. What the figures are is the API's (`GetUpcomingProjectionTest`).
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class UpcomingScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private fun open(upcoming: FakeUpcoming) {
        compose.setContent {
            FinanceDashboardScreen(
                viewModel = FakeLoadedFinanceDashboard(FinanceDashboard(year = 2026, month = 10)).viewModel,
                upcomingViewModel = upcoming.viewModel,
                onOpen = {},
            )
        }
    }

    // The blocks are lazy: one that is far down is not composed until the list is scrolled to it.
    private fun reveal(matcher: SemanticsMatcher) {
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(matcher)
    }

    private fun openUpcoming(upcoming: FakeUpcoming = FakeUpcoming(upcomingFixture())) {
        open(upcoming)
        compose.onNodeWithTag(UiTags.subview("upcoming")).performClick()
    }

    @Test
    fun `the dashboard opens on the current state, the upcoming blocks are not drawn`() {
        open(FakeUpcoming(upcomingFixture()))

        compose.onNodeWithTag(UiTags.subview("current")).assertIsSelected()
        compose.onNodeWithTag(UiTags.FINANCE_TILE_ACCOUNTS).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(UiTags.UPCOMING_WEEK).assertDoesNotExist()
    }

    @Test
    fun `the row switches to the upcoming blocks and back to the current state`() {
        openUpcoming()

        compose.onNodeWithTag(UiTags.UPCOMING_WEEK).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.FINANCE_TILE_ACCOUNTS).assertDoesNotExist()

        compose.onNodeWithTag(UiTags.subview("current")).performClick()

        reveal(hasTestTag(UiTags.FINANCE_TILE_ACCOUNTS))
        compose.onNodeWithTag(UiTags.FINANCE_TILE_ACCOUNTS).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.UPCOMING_WEEK).assertDoesNotExist()
    }

    @Test
    fun `the week and the month draw their net, their lines with the source and the day bars`() {
        openUpcoming()

        compose.onNodeWithText("La semaine suivante").assertIsDisplayed()
        compose.onNodeWithText("+${formatCents(248000)}").assertIsDisplayed()
        reveal(hasText("Marché"))
        compose.onNodeWithText("Marché").assertIsDisplayed()
        reveal(hasTestTag(UiTags.upcomingDays("week")))
        compose.onNodeWithTag(UiTags.upcomingDays("week")).assertIsDisplayed()

        reveal(hasText("Le mois suivant"))
        compose.onNodeWithText("Le mois suivant").assertIsDisplayed()
        reveal(hasText("Novembre 2026"))
        compose.onNodeWithText("Novembre 2026").assertIsDisplayed()
        reveal(hasText("Loyer"))
        compose.onNodeWithText("Loyer").assertIsDisplayed()
        reveal(hasText("Dentiste"))
        compose.onNodeWithText("Dentiste").assertIsDisplayed()
        reveal(hasTestTag(UiTags.upcomingDays("month")))
        compose.onNodeWithTag(UiTags.upcomingDays("month")).assertIsDisplayed()
        assertTrue(compose.onAllNodesWithText("récurrente").fetchSemanticsNodes().isNotEmpty())
        assertTrue(compose.onAllNodesWithText("prévue").fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun `an envelope committed beyond its amount says so, and what is outside the envelopes has its line`() {
        openUpcoming()

        reveal(hasTestTag(UiTags.UPCOMING_ENVELOPES))
        compose.onNodeWithTag(UiTags.UPCOMING_ENVELOPES).assertIsDisplayed()
        listOf("cat-courses", "cat-logement", "cat-loisirs").forEach { category ->
            reveal(hasTestTag(UiTags.upcomingEnvelope(category)))
            compose.onNodeWithTag(UiTags.upcomingEnvelope(category)).assertIsDisplayed()
        }
        reveal(hasText("${formatCents(90000)} engagés, au-delà de l'enveloppe"))
        compose.onNodeWithText("${formatCents(90000)} engagés, au-delà de l'enveloppe").assertIsDisplayed()
        reveal(hasText("${formatCents(8000)} engagés"))
        compose.onNodeWithText("${formatCents(8000)} engagés").assertIsDisplayed()
        reveal(hasTestTag(UiTags.UPCOMING_UNBUDGETED))
        compose.onNodeWithTag(UiTags.UPCOMING_UNBUDGETED).assertIsDisplayed()
        compose.onNodeWithText("Hors enveloppe").assertIsDisplayed()
        compose.onNodeWithText(formatCents(1200)).assertIsDisplayed()
    }

    @Test
    fun `what is left toward the projects is the headline of its card`() {
        openUpcoming()

        reveal(hasTestTag(UiTags.UPCOMING_REMAINING))
        compose.onNodeWithTag(UiTags.UPCOMING_REMAINING).assertIsDisplayed()
        compose.onNodeWithText("Reste vers les projets").assertIsDisplayed()
        compose.onNodeWithText(formatCents(98800)).assertIsDisplayed()
    }

    @Test
    fun `a month that is short says so without blame`() {
        val short = upcomingFixture().let { it.copy(month = it.month.copy(remainingCents = -5000)) }
        openUpcoming(FakeUpcoming(short))

        reveal(hasTestTag(UiTags.UPCOMING_REMAINING))
        compose.onNodeWithTag(UiTags.UPCOMING_REMAINING).assertIsDisplayed()
        compose.onNodeWithText(formatCents(-5000)).assertIsDisplayed()
        compose.onNodeWithText("Le mois est court de ${formatCents(5000)}.").assertIsDisplayed()
    }

    @Test
    fun `nothing coming draws the empty states and no envelope line`() {
        val empty = UpcomingProjection(
            today = "2026-10-16",
            week = UpcomingWeek(from = "2026-10-19", until = "2026-10-26"),
            month = UpcomingMonth(from = "2026-11-01", until = "2026-12-01", month = "2026-11"),
        )
        openUpcoming(FakeUpcoming(empty))

        compose.onNodeWithText("Rien de prévu la semaine suivante.").assertIsDisplayed()
        reveal(hasText("Rien de prévu le mois suivant."))
        compose.onNodeWithText("Rien de prévu le mois suivant.").assertIsDisplayed()
        reveal(hasText("Aucune enveloppe sur ce mois."))
        compose.onNodeWithText("Aucune enveloppe sur ce mois.").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.UPCOMING_UNBUDGETED).assertDoesNotExist()
        compose.onNodeWithTag(UiTags.upcomingDays("week")).assertDoesNotExist()
    }

    @Test
    fun `a failed read draws the error with a retry that loads the blocks`() {
        val upcoming = FakeUpcoming(projection = null, error = "Service indisponible")
        openUpcoming(upcoming)

        compose.onNodeWithTag(UiTags.UPCOMING_ERROR).assertIsDisplayed()
        compose.onNodeWithText("Service indisponible").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.UPCOMING_WEEK).assertDoesNotExist()

        upcoming.projection = upcomingFixture()
        compose.onNodeWithTag(UiTags.UPCOMING_RETRY).performClick()
        compose.waitForIdle()

        compose.onNode(hasTestTag(UiTags.UPCOMING_WEEK)).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.UPCOMING_ERROR).assertDoesNotExist()
    }
}

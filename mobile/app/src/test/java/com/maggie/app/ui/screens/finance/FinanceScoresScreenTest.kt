package com.maggie.app.ui.screens.finance

import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.FinanceDashboard
import com.maggie.app.data.model.FinanceScores
import com.maggie.app.data.model.NextProject
import com.maggie.app.data.model.PeriodScore
import com.maggie.app.data.model.Rhythm
import com.maggie.app.data.model.RhythmDebit
import com.maggie.app.data.model.RhythmPoint
import com.maggie.app.data.model.ScoreReason
import com.maggie.app.data.model.formatCents
import com.maggie.app.screentest.FakeUpcoming
import com.maggie.app.screentest.FakeLoadedFinanceDashboard
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.components.RhythmCurvePanel
import com.maggie.app.ui.components.ScoreRings
import com.maggie.app.ui.components.expectedLine
import com.maggie.app.ui.components.ringRhythmStatus
import com.maggie.app.ui.navigation.DeepLinks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The rings and the curve of the finance dashboard (MAG-417): drawn, opened by a tap, and
 * bearable when the scores cannot be loaded.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class FinanceScoresScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private val monthCurve = (1..31).map { day ->
        val date = "2026-03-%02d".format(day)
        RhythmPoint(
            date = date,
            spentCents = when {
                day < 5 -> 0
                day < 13 -> 90000
                day == 13 -> 120000
                day <= 15 -> 120000
                else -> null
            },
            expectedCents = if (day < 5) 0 else 90000 + (day - 5) * 1000,
        )
    }

    private val month = PeriodScore(
        status = "orange",
        label = "Attention",
        reasons = listOf(
            ScoreReason(code = "plans_exceed_category_budget", categoryName = "Voyages", amountCents = 21000),
            ScoreReason(code = "optional_category_exceeded", categoryName = "Loisirs", amountCents = 4500),
        ),
        rhythm = Rhythm(
            spentCents = 120000,
            expectedSpentCents = 99000,
            plannedCents = 120000,
            curve = monthCurve,
            debits = listOf(RhythmDebit(date = "2026-03-05", label = "Loyer", amountCents = 90000)),
        ),
    )

    private val week = PeriodScore(
        status = "neutral",
        label = "Dans les clous",
        reasons = emptyList(),
        rhythm = Rhythm(
            spentCents = 3000,
            expectedSpentCents = 4500,
            plannedCents = 9000,
            curve = listOf(
                RhythmPoint("2026-03-09", spentCents = 0, expectedCents = 0),
                RhythmPoint("2026-03-10", spentCents = 3000, expectedCents = 4500),
                RhythmPoint("2026-03-11", spentCents = null, expectedCents = 9000),
            ),
        ),
    )

    private val scores = FinanceScores(date = "2026-03-15", week = week, month = month)

    @Test
    fun `each ring says what was spent and what is expected, in clear and without a verdict`() {
        compose.setContent { ScoreRings(week = week, month = month) }

        compose.onNodeWithTag(UiTags.financeScoreRing("week")).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.financeScoreRing("month")).assertIsDisplayed()
        compose.onNodeWithText(formatCents(3000), substring = true).assertIsDisplayed()
        compose.onNodeWithText(formatCents(120000), substring = true).assertIsDisplayed()
        compose.onAllNodesWithText("dépensés").assertCountEquals(2)
        compose.onNodeWithText(expectedLine(week.rhythm)!!).assertIsDisplayed()
        compose.onNodeWithText(expectedLine(month.rhythm)!!).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.financeScoreRing("month")).assertContentDescriptionContains(
            "Mois : ${formatCents(120000)} dépensés, ${formatCents(99000)} attendus. Afficher les raisons",
        )
        for (word in listOf("Dans le rythme", "Au-dessus du rythme", "Rythme incomplet", "restant", "dépassé")) {
            compose.onAllNodesWithText(word, substring = true).assertCountEquals(0)
        }
    }

    @Test
    fun `a ring is above the rhythm once the spending passes what was expected, and on track otherwise`() {
        assertEquals("above", ringRhythmStatus(Rhythm(spentCents = 250830, expectedSpentCents = 120299, plannedCents = 240000)))
        assertEquals("on_track", ringRhythmStatus(Rhythm(spentCents = 3000, expectedSpentCents = 4500, plannedCents = 9000)))
        assertEquals("on_track", ringRhythmStatus(Rhythm(spentCents = 4500, expectedSpentCents = 4500, plannedCents = 9000)))
        assertEquals("on_track", ringRhythmStatus(Rhythm(spentCents = 1349)))
    }

    @Test
    fun `a period with nothing planned shows its spending and no expected line`() {
        val unplanned = week.copy(rhythm = Rhythm(spentCents = 1349))
        compose.setContent { ScoreRings(week = unplanned, month = unplanned) }

        compose.onAllNodesWithText(formatCents(1349), substring = true).assertCountEquals(2)
        compose.onAllNodesWithText("Attendu", substring = true).assertCountEquals(0)
    }

    @Test
    fun `touching a ring opens the reasons of its score, touching it again closes them`() {
        compose.setContent { ScoreRings(week = week, month = month) }
        compose.onNodeWithTag(UiTags.FINANCE_SCORE_REASONS).assertDoesNotExist()

        compose.onNodeWithTag(UiTags.financeScoreRing("month")).performClick()

        compose.onNodeWithText("• Les dépenses planifiées feraient dépasser Voyages de ${formatCents(21000)}").assertIsDisplayed()
        compose.onNodeWithText("• Loisirs dépassée de ${formatCents(4500)}").assertIsDisplayed()

        compose.onNodeWithTag(UiTags.financeScoreRing("week")).performClick()

        compose.onNodeWithText("Rien à signaler.").assertIsDisplayed()
        compose.onNodeWithText("• Loisirs dépassée de ${formatCents(4500)}").assertDoesNotExist()

        compose.onNodeWithTag(UiTags.financeScoreRing("week")).performClick()

        compose.onNodeWithTag(UiTags.FINANCE_SCORE_REASONS).assertDoesNotExist()
    }

    @Test
    fun `a period without envelope says so instead of dividing by zero`() {
        val none = PeriodScore(status = "neutral", label = "Dans les clous", reasons = listOf(ScoreReason("no_budget")))
        compose.setContent { ScoreRings(week = none, month = none) }

        compose.onNodeWithTag(UiTags.financeScoreRing("week")).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.financeScoreRing("week")).performClick()
        compose.onNodeWithText("• Aucune enveloppe sur cette période : rien à comparer").assertIsDisplayed()
    }

    private val unclassifiedMonth = month.copy(
        rhythm = month.rhythm.copy(unclassifiedCount = 4, unclassifiedCents = 200000),
    )

    @Test
    fun `debits left unclassified are said under their ring, in the secondary style, and Classer opens the review`() {
        var classified = 0
        compose.setContent { ScoreRings(week = week, month = unclassifiedMonth, onClassify = { classified++ }) }

        compose.onNodeWithTag(UiTags.financeScoreUnclassified("month")).assertIsDisplayed()
        compose.onNodeWithText("${formatCents(200000)} non classés", substring = true).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.financeScoreUnclassified("week")).assertDoesNotExist()
        compose.onAllNodesWithText("Rythme incomplet", substring = true).assertCountEquals(0)

        compose.onNodeWithTag(UiTags.financeScoreClassify("month")).performClick()

        assertEquals(1, classified)
    }

    @Test
    fun `Classer keeps its line under a ring when the amount takes the width, never squeezed to one letter per line`() {
        val wide = month.copy(rhythm = month.rhythm.copy(unclassifiedCount = 9, unclassifiedCents = 248231))
        val busyWeek = week.copy(rhythm = week.rhythm.copy(unclassifiedCount = 3, unclassifiedCents = 51234))
        compose.setContent { ScoreRings(week = busyWeek, month = wide) }

        val classify = compose.onNodeWithTag(UiTags.financeScoreClassify("month"))
        classify.assertIsDisplayed()
        val width = classify.getBoundsInRoot().width
        assertTrue("Classer is squeezed to $width", width > 40.dp)
    }

    @Test
    fun `no unclassified line when every debit has a category`() {
        compose.setContent { ScoreRings(week = week, month = month) }

        compose.onNodeWithTag(UiTags.financeScoreUnclassified("month")).assertDoesNotExist()
        compose.onNodeWithTag(UiTags.financeScoreUnclassified("week")).assertDoesNotExist()
    }

    @Test
    fun `the dashboard sends Classer to the lines to sort`() {
        var opened: String? = null
        val withUnclassified = scores.copy(month = unclassifiedMonth)
        compose.setContent {
            FinanceDashboardScreen(
                upcomingViewModel = FakeUpcoming().viewModel,
                viewModel = FakeLoadedFinanceDashboard(FinanceDashboard(year = 2026, month = 3, scores = withUnclassified)).viewModel,
                onOpen = { opened = it },
            )
        }

        compose.onNodeWithTag(UiTags.financeScoreClassify("month")).performScrollTo().performClick()

        assertEquals(DeepLinks.REVIEW_TO_SORT_ROUTE, opened)
    }

    @Test
    fun `the curve of the month marks the rent on its day`() {
        compose.setContent { RhythmCurvePanel(week = week.rhythm, month = month.rhythm) }

        compose.onNodeWithTag(UiTags.FINANCE_RHYTHM_CURVE).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.financeRhythmDebit("2026-03-05")).assertIsDisplayed()
        compose.onNodeWithText("Loyer · 5").assertIsDisplayed()
        compose.onAllNodesWithText("Au-dessus du rythme", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("Rythme incomplet", substring = true).assertCountEquals(0)
    }

    @Test
    fun `the week can be chosen on the curve`() {
        compose.setContent { RhythmCurvePanel(week = week.rhythm, month = month.rhythm) }
        compose.onNodeWithTag(UiTags.financeRhythmPeriod("month")).assertIsSelected()
        compose.onNodeWithTag(UiTags.financeRhythmPeriod("week")).assertIsNotSelected()

        compose.onNodeWithTag(UiTags.financeRhythmPeriod("week")).performClick()

        compose.onNodeWithTag(UiTags.financeRhythmPeriod("week")).assertIsSelected()
        compose.onNodeWithTag(UiTags.financeRhythmDebit("2026-03-05")).assertDoesNotExist()
    }

    @Test
    fun `a period without a curve says so`() {
        compose.setContent { RhythmCurvePanel(week = Rhythm(), month = Rhythm()) }

        compose.onNodeWithText("Pas de courbe pour cette période.").assertIsDisplayed()
    }

    private fun dashboardWithScores() = FinanceDashboard(year = 2026, month = 3, scores = scores)

    @Test
    fun `the dashboard draws the rings and the curve in place of the old daily score`() {
        compose.setContent {
            FinanceDashboardScreen(
                upcomingViewModel = FakeUpcoming().viewModel,
                viewModel = FakeLoadedFinanceDashboard(dashboardWithScores()).viewModel,
                onOpen = {},
            )
        }

        compose.onNodeWithTag(UiTags.financeScoreRing("week")).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.financeScoreRing("month")).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.FINANCE_RHYTHM_CURVE).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a payload without scores draws no ring and the rest of the dashboard goes on`() {
        compose.setContent {
            FinanceDashboardScreen(
                upcomingViewModel = FakeUpcoming().viewModel,
                viewModel = FakeLoadedFinanceDashboard(FinanceDashboard(year = 2026, month = 3)).viewModel,
                onOpen = {},
            )
        }

        compose.onNodeWithTag(UiTags.financeScoreRing("week")).assertDoesNotExist()
        compose.onNodeWithTag(UiTags.FINANCE_TILE_ACCOUNTS).performScrollTo().assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun `on a wide window the scores and the tiles are both on screen`() {
        compose.setContent {
            FinanceDashboardScreen(
                upcomingViewModel = FakeUpcoming().viewModel,
                viewModel = FakeLoadedFinanceDashboard(
                    dashboardWithScores().copy(nextProject = NextProject("p1", "Vélo", 100_000, 74_000, "2026-12")),
                ).viewModel,
                onOpen = {},
            )
        }

        compose.onNodeWithTag(UiTags.financeScoreRing("month")).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.FINANCE_TILE_ACCOUNTS).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.FINANCE_NEXT_PROJECT).assertIsDisplayed()
    }
}

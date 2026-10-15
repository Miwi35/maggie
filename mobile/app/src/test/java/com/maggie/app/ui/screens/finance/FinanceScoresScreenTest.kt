package com.maggie.app.ui.screens.finance

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.FinanceDashboard
import com.maggie.app.data.model.FinanceScores
import com.maggie.app.data.model.PeriodScore
import com.maggie.app.data.model.Rhythm
import com.maggie.app.data.model.RhythmDebit
import com.maggie.app.data.model.RhythmPoint
import com.maggie.app.data.model.ScoreReason
import com.maggie.app.data.model.formatCents
import com.maggie.app.screentest.FakeLoadedFinanceDashboard
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.components.RhythmCurvePanel
import com.maggie.app.ui.components.ScoreRings
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
            ScoreReason(code = "above_rhythm", amountCents = 21000),
            ScoreReason(code = "optional_category_exceeded", categoryName = "Loisirs", amountCents = 4500),
        ),
        rhythm = Rhythm(
            budgetCents = 150000,
            spentCents = 120000,
            expectedSpentCents = 99000,
            remainingCents = 30000,
            expectedRemainingCents = 51000,
            status = "above",
            label = "Au-dessus du rythme",
            curve = monthCurve,
            debits = listOf(RhythmDebit(date = "2026-03-05", label = "Loyer", amountCents = 90000)),
        ),
    )

    private val week = PeriodScore(
        status = "green",
        label = "Dans le vert",
        reasons = listOf(ScoreReason(code = "below_rhythm", amountCents = 1500)),
        rhythm = Rhythm(
            budgetCents = 20000,
            spentCents = 3000,
            expectedSpentCents = 4500,
            remainingCents = 17000,
            expectedRemainingCents = 15500,
            label = "Dans le rythme",
            curve = listOf(
                RhythmPoint("2026-03-09", spentCents = 0, expectedCents = 0),
                RhythmPoint("2026-03-10", spentCents = 3000, expectedCents = 4500),
                RhythmPoint("2026-03-11", spentCents = null, expectedCents = 9000),
            ),
        ),
    )

    private val scores = FinanceScores(date = "2026-03-15", week = week, month = month)

    @Test
    fun `each ring says its status in words and its amounts in clear`() {
        compose.setContent { ScoreRings(week = week, month = month) }

        compose.onNodeWithTag(UiTags.financeScoreRing("week")).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.financeScoreRing("month")).assertIsDisplayed()
        compose.onNodeWithText("Dans le vert", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Attention", substring = true).assertIsDisplayed()
        compose.onNodeWithText(formatCents(17000), substring = true).assertIsDisplayed()
        compose.onNodeWithText(formatCents(30000), substring = true).assertIsDisplayed()
    }

    @Test
    fun `touching a ring opens the reasons of its score, touching it again closes them`() {
        compose.setContent { ScoreRings(week = week, month = month) }
        compose.onNodeWithTag(UiTags.FINANCE_SCORE_REASONS).assertDoesNotExist()

        compose.onNodeWithTag(UiTags.financeScoreRing("month")).performClick()

        compose.onNodeWithText("• ${formatCents(21000)} de plus que le rythme attendu").assertIsDisplayed()
        compose.onNodeWithText("• Loisirs dépassée de ${formatCents(4500)}").assertIsDisplayed()

        compose.onNodeWithTag(UiTags.financeScoreRing("week")).performClick()

        compose.onNodeWithText("• ${formatCents(1500)} de moins que le rythme attendu").assertIsDisplayed()
        compose.onNodeWithText("• Loisirs dépassée de ${formatCents(4500)}").assertDoesNotExist()

        compose.onNodeWithTag(UiTags.financeScoreRing("week")).performClick()

        compose.onNodeWithTag(UiTags.FINANCE_SCORE_REASONS).assertDoesNotExist()
    }

    @Test
    fun `an overspent month shows a negative amount and says it is exceeded`() {
        val over = month.copy(
            status = "red",
            label = "Dépassement",
            rhythm = month.rhythm.copy(remainingCents = -91349),
        )
        compose.setContent { ScoreRings(week = week, month = over) }

        compose.onNodeWithText(formatCents(-91349), substring = true).assertIsDisplayed()
        compose.onNodeWithText("Dépassement", substring = true).assertIsDisplayed()
        compose.onNodeWithText("dépassé").assertIsDisplayed()
    }

    @Test
    fun `a period without envelope says so instead of dividing by zero`() {
        val none = PeriodScore(status = "neutral", label = "Dans les clous", reasons = listOf(ScoreReason("no_budget")))
        compose.setContent { ScoreRings(week = none, month = none) }

        compose.onNodeWithTag(UiTags.financeScoreRing("week")).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.financeScoreRing("week")).performClick()
        compose.onNodeWithText("• Aucune enveloppe sur cette période : rien à comparer").assertIsDisplayed()
    }

    @Test
    fun `the curve of the month marks the rent on its day`() {
        compose.setContent { RhythmCurvePanel(week = week.rhythm, month = month.rhythm) }

        compose.onNodeWithTag(UiTags.FINANCE_RHYTHM_CURVE).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.financeRhythmDebit("2026-03-05")).assertIsDisplayed()
        compose.onNodeWithText("Loyer · 5").assertIsDisplayed()
        compose.onNodeWithText("Au-dessus du rythme", substring = true).assertIsDisplayed()
    }

    @Test
    fun `the week can be chosen on the curve`() {
        compose.setContent { RhythmCurvePanel(week = week.rhythm, month = month.rhythm) }
        compose.onNodeWithTag(UiTags.financeRhythmPeriod("month")).assertIsSelected()
        compose.onNodeWithTag(UiTags.financeRhythmPeriod("week")).assertIsNotSelected()

        compose.onNodeWithTag(UiTags.financeRhythmPeriod("week")).performClick()

        compose.onNodeWithTag(UiTags.financeRhythmPeriod("week")).assertIsSelected()
        compose.onNodeWithText("Dans le rythme", substring = true).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.financeRhythmDebit("2026-03-05")).assertDoesNotExist()
    }

    @Test
    fun `a period without a curve says so`() {
        compose.setContent { RhythmCurvePanel(week = Rhythm(), month = Rhythm()) }

        compose.onNodeWithText("Pas de courbe pour cette période.").assertIsDisplayed()
    }

    @Test
    fun `the dashboard draws the rings and the curve in place of the old daily score`() {
        compose.setContent {
            FinanceDashboardScreen(
                viewModel = FakeLoadedFinanceDashboard(FinanceDashboard(year = 2026, month = 3), scores = scores).viewModel,
                onBack = {},
                onOpen = {},
            )
        }

        compose.onNodeWithTag(UiTags.financeScoreRing("week")).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.financeScoreRing("month")).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.FINANCE_RHYTHM_CURVE).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `scores that cannot be loaded say so in their place and the rest of the dashboard goes on`() {
        val opened = mutableListOf<String>()
        compose.setContent {
            FinanceDashboardScreen(
                viewModel = FakeLoadedFinanceDashboard(FinanceDashboard(year = 2026, month = 3)).viewModel,
                onBack = {},
                onOpen = { opened += it },
            )
        }

        compose.onNodeWithText("Scores indisponibles").assertIsDisplayed()
        compose.onNodeWithText("Scores en panne").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.FINANCE_SCORES_RETRY).assertIsDisplayed()
        compose.onNodeWithText("Solde de tous les comptes").performScrollTo().assertIsDisplayed()
        assertTrue(opened.isEmpty())
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun `on a wide window the scores and the figures are both on screen`() {
        compose.setContent {
            FinanceDashboardScreen(
                viewModel = FakeLoadedFinanceDashboard(FinanceDashboard(year = 2026, month = 3), scores = scores).viewModel,
                onBack = {},
                onOpen = {},
            )
        }

        compose.onNodeWithTag(UiTags.financeScoreRing("month")).assertIsDisplayed()
        compose.onNodeWithText("Solde de tous les comptes").assertIsDisplayed()
    }
}

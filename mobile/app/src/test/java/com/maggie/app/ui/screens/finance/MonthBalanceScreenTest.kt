package com.maggie.app.ui.screens.finance

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.BalanceEnvelope
import com.maggie.app.data.model.BalanceSatisfaction
import com.maggie.app.data.model.BalanceSavings
import com.maggie.app.data.model.BalanceToQualify
import com.maggie.app.data.model.MonthBalance
import com.maggie.app.data.model.PeriodScore
import com.maggie.app.data.model.Rhythm
import com.maggie.app.data.model.RhythmPoint
import com.maggie.app.data.model.TileCushion
import com.maggie.app.data.model.formatCents
import com.maggie.app.screentest.FakeLoadedFinanceDashboard
import com.maggie.app.screentest.FakeMonthBalance
import com.maggie.app.screentest.FakeUpcoming
import com.maggie.app.data.model.FinanceDashboard
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.components.ringAmount
import com.maggie.app.ui.navigation.DeepLinks
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Dashboard › État courant (MAG-457, MAG-537): the ring, the curve, the bars, the satisfaction, the savings and
 * the cushion are drawn from one balance; an empty month says so instead of showing zeros.
 *
 * What the figures are is the API's (`MonthBalanceControllerTest`); what is checked here is that they
 * are drawn, that the unknown ones say so, and that the taps ask for the right route.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class MonthBalanceScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private val balance = MonthBalance(
        month = "2026-09",
        spending = PeriodScore(
            status = "red",
            label = "Dépassement",
            rhythm = Rhythm(
                budgetCents = 70000,
                spentCents = 85000,
                remainingCents = -15000,
                expectedRemainingCents = 0,
                status = "above",
                label = "Au-dessus du rythme",
                curve = listOf(
                    RhythmPoint("2026-09-01", spentCents = 0, expectedCents = 0),
                    RhythmPoint("2026-09-02", spentCents = 85000, expectedCents = 2300),
                ),
            ),
        ),
        envelopes = listOf(
            BalanceEnvelope("e1", "Alimentation", budgetCents = 40000, consumedCents = 20000, percent = 50, status = "held"),
            BalanceEnvelope("e2", "Loisirs", budgetCents = 20000, consumedCents = 17000, percent = 85, status = "close"),
            BalanceEnvelope("e3", "Transport", budgetCents = 10000, consumedCents = 15000, percent = 150, status = "over"),
        ),
        satisfaction = BalanceSatisfaction(judgedCents = 17000, keptCents = 12000, avoidableCents = 5000, unratedCount = 3, keptPercent = 71),
        savings = BalanceSavings(savedCents = 30000, plannedCents = 50000),
        cushion = TileCushion(state = "building", targetCents = 600000, currentCents = 420000, coveragePercent = 70, isConfigured = true),
        toQualify = BalanceToQualify(count = 1, cents = 4000),
    )

    private val emptyMonth = MonthBalance(
        month = "2026-01",
        satisfaction = BalanceSatisfaction(),
        savings = BalanceSavings(savedCents = 0, plannedCents = null),
        cushion = TileCushion(),
    )

    private fun open(
        fake: FakeMonthBalance = FakeMonthBalance(balance),
        onOpen: (String) -> Unit = {},
    ) {
        compose.setContent {
            FinanceDashboardScreen(
                viewModel = FakeLoadedFinanceDashboard(FinanceDashboard(year = 2026, month = 9), today = LocalDate.of(2026, 9, 17)).viewModel,
                monthBalanceViewModel = fake.viewModel,
                upcomingViewModel = FakeUpcoming().viewModel,
                onOpen = onOpen,
            )
        }
    }

    @Test
    fun `the month ring says its status and what is over, and the curve is drawn`() {
        open()

        compose.onNodeWithTag(UiTags.financeScoreRing("month")).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Au-dessus du rythme", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(ringAmount(-15000), substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(UiTags.FINANCE_RHYTHM_CURVE).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `each envelope bar says held, close or over in words`() {
        open()

        listOf("e1" to "✓ Tenue", "e2" to "▲ Proche", "e3" to "✕ Dépassée").forEach { (id, word) ->
            compose.onNodeWithTag(UiTags.monthBalanceEnvelope(id)).performScrollTo().assertIsDisplayed()
            compose.onNodeWithContentDescription(word, substring = true).assertIsDisplayed()
        }
        compose.onNodeWithText("${formatCents(15000)} sur ${formatCents(10000)} · 150 %").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `the satisfaction shows the kept share and the superfluous amount`() {
        open()

        compose.onNodeWithText("71 %").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Superflu : ${formatCents(5000)}").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("3 à juger").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `the savings and the cushion are drawn`() {
        open()

        compose.onNodeWithText("${formatCents(30000)} épargnés sur ${formatCents(50000)} prévus").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("${formatCents(420000)} sur ${formatCents(600000)} · 70 %").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `the lines to qualify are reported apart, and the pill opens the page that qualifies them`() {
        var opened: String? = null
        open(onOpen = { opened = it })

        compose.onNodeWithText("À qualifier · 1 · ${formatCents(4000)}").assertIsDisplayed().performClick()

        assertEquals(DeepLinks.REVIEW_TO_SORT_ROUTE, opened)
    }

    @Test
    fun `an empty month shows no pill and says so instead of showing zeros`() {
        open(FakeMonthBalance(emptyMonth))

        compose.onNodeWithTag(UiTags.TO_QUALIFY_PILL).assertDoesNotExist()
        compose.onNodeWithTag(UiTags.MONTH_BALANCE_ENVELOPES_EMPTY).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(UiTags.MONTH_BALANCE_SATISFACTION_EMPTY).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("0 %").assertDoesNotExist()
        compose.onNodeWithText("capacité inconnue", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Matelas non configuré.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `changing the month asks for the other one`() {
        open()

        compose.onNodeWithText("Septembre 2026").assertIsDisplayed()
        compose.onNodeWithContentDescription("Mois précédent").performClick()

        compose.onNodeWithText("Août 2026").assertIsDisplayed()
        compose.onNodeWithText("Bilan de août 2026").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a balance that cannot be loaded says so`() {
        open(FakeMonthBalance(balance = null, error = "Service indisponible"))

        compose.onNodeWithTag(UiTags.MONTH_BALANCE_EMPTY).assertIsDisplayed()
    }

    @Test
    fun `the balance sits under État courant, and À venir takes it away`() {
        open()

        compose.onNodeWithTag(UiTags.subview("current")).assertIsSelected()
        compose.onNodeWithTag(UiTags.financeScoreRing("month")).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(UiTags.subview("upcoming")).performClick()

        compose.onNodeWithTag(UiTags.subview("upcoming")).assertIsSelected()
        compose.onNodeWithTag(UiTags.MONTH_BALANCE_ENVELOPES).assertDoesNotExist()
    }
}

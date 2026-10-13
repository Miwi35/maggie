package com.maggie.app.ui.screens.finance

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.MissedOccurrence
import com.maggie.app.data.model.PriceChange
import com.maggie.app.data.model.RecurringSeries
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The two warnings of a series. The journey (`10-finance-recurring`) asserts that the badge is
 * there by its tag, not by its words, so the tag has to be on the drawn badge.
 */
@RunWith(AndroidJUnit4::class)
class RecurringOperationScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private val calm = RecurringSeries(
        id = "gym",
        label = "Salle de sport",
        referenceAmountCents = -3000,
        monthlyCostCents = -3000,
        yearlyCostCents = -36000,
        nextOccurrenceOn = "2026-05-02",
    )

    @Test
    fun `a missed due date shows its badge with the days of delay`() {
        val series = calm.copy(missedOccurrence = MissedOccurrence(occurrenceOn = "2026-04-02", daysOverdue = 33))
        compose.setContent { SeriesCard(series = series, record = null) }

        compose.onNodeWithTag(UiTags.RECURRING_MISSED_BADGE).assertIsDisplayed()
        compose.onNodeWithText("Échéance manquée", substring = true).assertIsDisplayed()
        compose.onNodeWithText("33 j", substring = true).assertIsDisplayed()
        compose.onAllNodesWithTag(UiTags.RECURRING_PRICE_BADGE).assertCountEquals(0)
    }

    @Test
    fun `a price increase shows its badge with the old and the new amount`() {
        val series = calm.copy(
            priceChange = PriceChange(previousCents = 1349, currentCents = 1799, percent = 33, since = "2026-03-01"),
        )
        compose.setContent { SeriesCard(series = series, record = null) }

        compose.onNodeWithTag(UiTags.RECURRING_PRICE_BADGE).assertIsDisplayed()
        compose.onNodeWithText("Hausse de prix", substring = true).assertIsDisplayed()
        compose.onNodeWithText("+33 %", substring = true).assertIsDisplayed()
        compose.onAllNodesWithTag(UiTags.RECURRING_MISSED_BADGE).assertCountEquals(0)
    }

    @Test
    fun `a price drop says so instead of calling it an increase`() {
        val series = calm.copy(
            priceChange = PriceChange(previousCents = 1799, currentCents = 1349, percent = -25, since = "2026-03-01"),
        )
        compose.setContent { SeriesCard(series = series, record = null) }

        compose.onNodeWithTag(UiTags.RECURRING_PRICE_BADGE).assertIsDisplayed()
        compose.onNodeWithText("Baisse de prix", substring = true).assertIsDisplayed()
        compose.onNodeWithText("-25 %", substring = true).assertIsDisplayed()
        compose.onAllNodesWithText("Hausse", substring = true).assertCountEquals(0)
    }

    @Test
    fun `a series with nothing wrong has no badge`() {
        compose.setContent { SeriesCard(series = calm, record = null) }
        compose.onNodeWithText("30.00 EUR par mois · 360.00 EUR par an").assertIsDisplayed()

        compose.onNodeWithText("Salle de sport").assertIsDisplayed()
        compose.onAllNodesWithTag(UiTags.RECURRING_MISSED_BADGE).assertCountEquals(0)
        compose.onAllNodesWithTag(UiTags.RECURRING_PRICE_BADGE).assertCountEquals(0)
    }
}

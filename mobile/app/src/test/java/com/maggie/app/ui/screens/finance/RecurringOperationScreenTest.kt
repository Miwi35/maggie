package com.maggie.app.ui.screens.finance

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.MissedOccurrence
import com.maggie.app.data.model.PriceChange
import com.maggie.app.data.model.RecurringSeries
import com.maggie.app.data.model.Transaction
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import org.junit.Assert.assertEquals
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

    private val line = Transaction(
        id = "tx-april", label = "LOYER SCI VERDA", amountCents = -90000, bookedAt = "2026-04-12",
        recurringOperation = "/api/recurring_operations/rent", recurringOccurrenceOn = "2026-04-05", recurringSource = "auto",
    )

    @Test
    fun `an attached line shows its amount, its date and the due date it covers`() {
        compose.setContent {
            SeriesLinesSection(lines = listOf(line), error = null, detachingId = null, onDetach = {}, onRetry = {})
        }

        compose.onNodeWithText("LOYER SCI VERDA").assertIsDisplayed()
        compose.onNodeWithText("-900.00 EUR").assertIsDisplayed()
        compose.onNodeWithText("Le 12/04/2026 · échéance du 05/04/2026 · automatique").assertIsDisplayed()
    }

    @Test
    fun `detaching a line hands its id to the screen`() {
        var detached: String? = null
        compose.setContent {
            SeriesLinesSection(lines = listOf(line), error = null, detachingId = null, onDetach = { detached = it }, onRetry = {})
        }

        compose.onNodeWithTag(UiTags.RECURRING_LINE_DETACH).performClick()

        assertEquals("tx-april", detached)
    }

    @Test
    fun `a detachment in progress locks the buttons`() {
        compose.setContent {
            SeriesLinesSection(lines = listOf(line), error = null, detachingId = "tx-april", onDetach = {}, onRetry = {})
        }

        compose.onNodeWithTag(UiTags.RECURRING_LINE_DETACH).assertIsNotEnabled()
    }

    @Test
    fun `a series with no line says so`() {
        compose.setContent {
            SeriesLinesSection(lines = emptyList(), error = null, detachingId = null, onDetach = {}, onRetry = {})
        }

        compose.onNodeWithText("Aucune transaction reconnue pour l’instant").assertIsDisplayed()
        compose.onAllNodesWithTag(UiTags.RECURRING_LINE).assertCountEquals(0)
    }

    @Test
    fun `a failed read shows the error and a retry`() {
        var retried = false
        compose.setContent {
            SeriesLinesSection(lines = null, error = "503", detachingId = null, onDetach = {}, onRetry = { retried = true })
        }

        compose.onNodeWithText("503").assertIsDisplayed()
        compose.onNodeWithText("Réessayer").performClick()

        assertEquals(true, retried)
    }
}

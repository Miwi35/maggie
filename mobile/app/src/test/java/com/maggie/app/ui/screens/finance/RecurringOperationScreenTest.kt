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
import com.maggie.app.data.model.Account
import com.maggie.app.data.model.MissedOccurrence
import com.maggie.app.data.model.PriceChange
import com.maggie.app.data.model.RecurringProposal
import com.maggie.app.data.model.RecurringSeries
import com.maggie.app.data.model.RecurringSuggestion
import com.maggie.app.data.model.RecurringTotals
import com.maggie.app.data.model.Transaction
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The cards of « À vérifier » and the dated rows. The journey (`10-finance-recurring`) finds the
 * badges by their tag, so the tag has to be on the drawn chip.
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

    private val nbsp = "\u00A0"

    private val rent = RecurringSeries(
        id = "rent", label = "Loyer", referenceAmountCents = 90000, monthlyCostCents = 90000,
        nextOccurrenceOn = "2026-10-05", period = "monthly", dayRule = "day_of_month",
    )

    private fun suggestion(categoryId: String? = null) = RecurringSuggestion(
        label = "Spotify", accountId = "a1", period = "monthly", anchorOn = "2026-08-05", dayRule = "day_of_month",
        referenceAmountCents = -2999, occurrences = 3, categoryId = categoryId,
    )

    @Test
    fun `the totals read per month and per year in whole euros`() {
        compose.setContent { RecurringTotalsLine(RecurringTotals(monthlyCostCents = -118200, yearlyCostCents = -1418400, activeCount = 4)) }

        compose.onNodeWithText("1\u202F182${nbsp}€ par mois", substring = true).assertIsDisplayed()
        compose.onNodeWithText("14\u202F184${nbsp}€ par an", substring = true).assertIsDisplayed()
        compose.onAllNodesWithText("Coût mensuel", substring = true).assertCountEquals(0)
    }

    @Test
    fun `a missed due date is a card with how late it is and what was expected`() {
        val series = calm.copy(
            label = "Free Mobile", referenceAmountCents = -1999, accountName = "Compte courant",
            missedOccurrence = MissedOccurrence(occurrenceOn = "2026-10-09", daysOverdue = 3),
        )
        compose.setContent { MissedCard(series) }

        compose.onNodeWithText("Free Mobile").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.RECURRING_MISSED_BADGE).assertIsDisplayed()
        compose.onNodeWithText("en retard de 3 jours").assertIsDisplayed()
        compose.onNodeWithText("Attendue le 9 oct. · 19,99${nbsp}€ · Compte courant", substring = true).assertIsDisplayed()
    }

    @Test
    fun `one day late is singular`() {
        val series = calm.copy(missedOccurrence = MissedOccurrence(occurrenceOn = "2026-10-09", daysOverdue = 1))
        compose.setContent { MissedCard(series) }

        compose.onNodeWithText("en retard de 1 jour").assertIsDisplayed()
    }

    @Test
    fun `a price increase is a card with the new and the old amount`() {
        val series = calm.copy(
            label = "Netflix",
            priceChange = PriceChange(previousCents = 1349, currentCents = 1799, percent = 33, since = "2026-10-11"),
        )
        compose.setContent { PriceChangedCard(series) }

        compose.onNodeWithTag(UiTags.RECURRING_PRICE_BADGE).assertIsDisplayed()
        compose.onNodeWithText("+33 %").assertIsDisplayed()
        compose.onNodeWithText("17,99${nbsp}€ au lieu de 13,49${nbsp}€ depuis le 11 oct.", substring = true).assertIsDisplayed()
    }

    @Test
    fun `a price drop shows its negative percent`() {
        val series = calm.copy(
            priceChange = PriceChange(previousCents = 1799, currentCents = 1349, percent = -25, since = "2026-10-11"),
        )
        compose.setContent { PriceChangedCard(series) }

        compose.onNodeWithText("-25 %").assertIsDisplayed()
        compose.onAllNodesWithText("+", substring = true).assertCountEquals(0)
    }

    @Test
    fun `checks lists a late series and a price change as two cards, and a calm series as none`() {
        val both = calm.copy(
            id = "both",
            missedOccurrence = MissedOccurrence(occurrenceOn = "2026-10-09", daysOverdue = 3),
            priceChange = PriceChange(previousCents = 1349, currentCents = 1799, percent = 33, since = "2026-10-11"),
        )

        val result = checks(listOf(calm, both))

        assertEquals(listOf("missed-both", "price-both"), result.map { it.key })
        assertEquals(emptyList<RecurringCheck>(), checks(listOf(calm)))
    }

    @Test
    fun `a check card opens its series`() {
        var opened = 0
        val series = calm.copy(missedOccurrence = MissedOccurrence(occurrenceOn = "2026-10-09", daysOverdue = 3))
        compose.setContent { MissedCard(series, onClick = { opened++ }) }

        compose.onNodeWithText("Salle de sport").performClick()

        assertEquals(1, opened)
    }

    @Test
    fun `a proposal offers to attach it or says no`() {
        var claimed: String? = null
        var dismissed = false
        val proposal = RecurringProposal(
            transactionId = "tx", label = "LOYER SCI VERDA", bookedAt = "2026-10-13",
            recurringOperationId = "rent", recurringOperationLabel = "Loyer", occurrenceOn = "2026-10-05", dateGapDays = 8,
        )
        compose.setContent {
            ProposalCard(proposal, busy = false, onAccept = { claimed = it.recurringOperationId }, onDismiss = { dismissed = true })
        }

        compose.onNodeWithText("à rattacher").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.RECURRING_PROPOSAL_ACCEPT).performClick()
        compose.onNodeWithText("Non").performClick()

        assertEquals("rent", claimed)
        assertEquals(true, dismissed)
        compose.onNodeWithText("Rattacher à « Loyer »").assertIsDisplayed()
    }

    @Test
    fun `a suggestion says how often it was seen and offers C'est une récurrente or Non`() {
        compose.setContent { SuggestionCard(suggestion(), RecurringOperationUiState(), {}, {}, {}) }

        compose.onNodeWithText("proposée").assertIsDisplayed()
        compose.onNodeWithText("29,99${nbsp}€ vus 3 fois · le 5 de chaque mois").assertIsDisplayed()
        compose.onNodeWithText("C'est une récurrente").assertIsDisplayed()
        compose.onNodeWithText("Non").assertIsDisplayed()
    }

    @Test
    fun `C'est une récurrente asks for a category when the suggestion has none`() {
        var chose = 0
        var accepted = 0
        compose.setContent { SuggestionCard(suggestion(), RecurringOperationUiState(), { chose++ }, { accepted++ }, {}) }

        compose.onNodeWithTag(UiTags.RECURRING_SUGGESTION_ACCEPT).performClick()

        assertEquals(1, chose)
        assertEquals(0, accepted)
    }

    @Test
    fun `C'est une récurrente accepts at once when the category is known`() {
        var chose = 0
        var accepted = 0
        compose.setContent { SuggestionCard(suggestion("cat"), RecurringOperationUiState(), { chose++ }, { accepted++ }, {}) }

        compose.onNodeWithTag(UiTags.RECURRING_SUGGESTION_ACCEPT).performClick()

        assertEquals(0, chose)
        assertEquals(1, accepted)
    }

    @Test
    fun `the next due dates are sorted by date, without a date last`() {
        val undated = calm.copy(id = "undated", nextOccurrenceOn = null)
        val late = calm.copy(id = "late", nextOccurrenceOn = "2026-11-02")
        val early = calm.copy(id = "early", nextOccurrenceOn = "2026-10-15T00:00:00+00:00")

        assertEquals(listOf("early", "late", "undated"), dueOrder(listOf(undated, late, early)).map { it.id })
    }

    @Test
    fun `a due row shows the day, the month, the schedule and the amount, an income with a plus`() {
        val salary = rent.copy(id = "salary", label = "Salaire", referenceAmountCents = 218000, nextOccurrenceOn = "2026-10-15", endsOn = "2027-01-31")
        compose.setContent { DueRow(salary) }

        compose.onNodeWithText("15").assertIsDisplayed()
        compose.onNodeWithText("oct.").assertIsDisplayed()
        compose.onNodeWithText("Salaire").assertIsDisplayed()
        compose.onNodeWithText("jusqu'au", substring = true).assertIsDisplayed()
        compose.onNodeWithText("+2\u202F180,00${nbsp}€").assertIsDisplayed()
    }

    @Test
    fun `a due row without a date shows no day`() {
        assertEquals(null, dueDayAndMonth(null))
        assertEquals("5" to "oct.", dueDayAndMonth("2026-10-05"))
    }

    @Test
    fun `an outgoing amount has no sign`() {
        compose.setContent { DueRow(rent.copy(referenceAmountCents = -6500)) }

        compose.onNodeWithText("65,00${nbsp}€").assertIsDisplayed()
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
        compose.onNodeWithText("\u2212900,00\u00A0€").assertIsDisplayed()
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

    @Test
    fun `the form reads the bank of the overview when the account has none of its own`() {
        val accounts = listOf(Account(id = "a1", name = "Courant", bank = null))
        val series = listOf(calm.copy(accountId = "a1", accountName = "Courant", accountBank = "Revolut"))

        assertEquals("Revolut · EUR · Courant", recurringAccountLabel(accounts, series, recurringAccountIri("a1")))
    }

    @Test
    fun `the form keeps the typed bank of the account`() {
        val accounts = listOf(Account(id = "a1", name = "Courant", bank = "Crédit Agricole"))
        val series = listOf(calm.copy(accountId = "a1", accountBank = "Revolut"))

        assertEquals("Crédit Agricole · EUR · Courant", recurringAccountLabel(accounts, series, recurringAccountIri("a1")))
    }

    @Test
    fun `the form shows only the name of an account without a bank anywhere`() {
        val accounts = listOf(Account(id = "a1", name = "Espèces"))

        assertEquals("EUR · Espèces", recurringAccountLabel(accounts, emptyList(), recurringAccountIri("a1")))
    }

    @Test
    fun `the account picker tells two accounts of the same name apart by their bank`() {
        val accounts = listOf(
            Account(id = "a1", name = "Compte courant", bank = "Boursorama"),
            Account(id = "a2", name = "Compte courant", bank = "Crédit Agricole"),
            Account(id = "a3", name = "Livret", bank = null),
        )
        compose.setContent {
            AccountSearchScreen(accounts = accounts, bankByAccount = emptyMap(), selected = null, onPick = {}, onBack = {})
        }

        compose.onNodeWithText("Boursorama · EUR · Compte courant").assertIsDisplayed()
        compose.onNodeWithText("Crédit Agricole · EUR · Compte courant").assertIsDisplayed()
        compose.onNodeWithText("EUR · Livret").assertIsDisplayed()
    }

    @Test
    fun `the account picker reads the bank of the overview when the account has none of its own`() {
        val accounts = listOf(Account(id = "a1", name = "Compte courant", bank = null))
        compose.setContent {
            AccountSearchScreen(accounts = accounts, bankByAccount = mapOf("a1" to "Revolut"), selected = null, onPick = {}, onBack = {})
        }

        compose.onNodeWithText("Revolut · EUR · Compte courant").assertIsDisplayed()
    }
}

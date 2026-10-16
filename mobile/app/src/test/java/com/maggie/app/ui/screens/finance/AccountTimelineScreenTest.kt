package com.maggie.app.ui.screens.finance

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.Account
import com.maggie.app.data.model.AccountTimeline
import com.maggie.app.data.model.RecurringIncrease
import com.maggie.app.data.model.TimelineAccount
import com.maggie.app.data.model.TimelineCounterpartAccount
import com.maggie.app.data.model.TimelineDay
import com.maggie.app.data.model.TimelineLine
import com.maggie.app.data.model.TimelineMissedOccurrence
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The account pager and its days: statuses in words, transfer badges, increases, late occurrences (MAG-422). */
@RunWith(AndroidJUnit4::class)
class AccountTimelineScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private val checking = Account(id = "a1", name = "Compte courant", bank = "Boursorama")
    private val savings = Account(id = "a2", name = "Livret")

    private fun timeline(vararg days: TimelineDay, manual: Boolean = true, previous: String? = null) = AccountTimelineState(
        pages = listOf(
            AccountTimeline(
                month = "2026-10",
                previousMonth = previous,
                account = TimelineAccount(id = "a1", name = "Compte courant", balanceCents = 125000, isManual = manual),
                days = days.toList(),
            ),
        ),
    )

    private fun show(ui: AccountUiState, viewModel: AccountViewModel = mockk(relaxed = true)): AccountViewModel {
        every { viewModel.uiState } returns MutableStateFlow(ui)
        compose.setContent { AccountPagerScreen(viewModel = viewModel, onBack = {}, actions = AccountTimelineActions { _, _, _ -> }) }
        return viewModel
    }

    private fun ui(state: AccountTimelineState) = AccountUiState(
        accounts = listOf(checking, savings),
        selectedAccountId = "a1",
        timelines = mapOf("a1" to state),
    )

    @Test
    fun `a day shows its lines with the status in words and the transfer names the other account`() {
        show(
            ui(
                timeline(
                    TimelineDay(
                        date = "2026-10-05",
                        transactions = listOf(
                            TimelineLine(id = "t1", label = "VIR LIVRET", amountCents = -5000, status = "spent", transferKind = "internal", counterpartAccount = TimelineCounterpartAccount("a2", "Livret")),
                            TimelineLine(id = "t2", label = "Courses", amountCents = -2000, status = "planned"),
                            TimelineLine(id = "t3", label = "Loyer", amountCents = -90000, status = "spent", recurringIncrease = RecurringIncrease(33, -67000)),
                        ),
                    ),
                ),
            ),
        )

        compose.onNodeWithText("VIR LIVRET").assertIsDisplayed()
        compose.onNodeWithText("vers Livret").assertIsDisplayed()
        compose.onNodeWithText("Planifiée").assertIsDisplayed()
        compose.onNodeWithText("+33 %").assertIsDisplayed()
    }

    @Test
    fun `a missed occurrence reads attendu le jour en retard`() {
        show(
            ui(
                timeline(
                    TimelineDay(
                        date = "2026-10-05",
                        missedOccurrences = listOf(TimelineMissedOccurrence("r1", "Loyer", "2026-10-05", -90000)),
                    ),
                ),
            ),
        )

        compose.onNodeWithText("Loyer attendu le 5 — en retard").assertIsDisplayed()
    }

    @Test
    fun `an error shows Réessayer which reads the account again`() {
        val viewModel = show(ui(AccountTimelineState(error = "hors ligne")))

        compose.onNodeWithText("Réessayer").performClick()

        verify { viewModel.loadTimeline("a1") }
    }

    @Test
    fun `an account without a line says so`() {
        show(ui(timeline()))

        compose.onNodeWithTag(UiTags.ACCOUNT_PAGE).assertIsDisplayed()
        compose.onNodeWithText("Mois précédent", substring = true).assertDoesNotExist()
    }

    @Test
    fun `Mois précédent loads the earlier month`() {
        val viewModel = show(ui(timeline(TimelineDay(date = "2026-10-05", transactions = listOf(TimelineLine("t1", "Courses"))), previous = "2026-09")))

        compose.onNodeWithTag(UiTags.TIMELINE_PREVIOUS_MONTH).performClick()

        verify { viewModel.loadPreviousMonth("a1") }
    }

    @Test
    fun `only a manual account offers the balance correction`() {
        show(ui(timeline(manual = false)))

        compose.onNodeWithTag(UiTags.ACCOUNT_CORRECT_BALANCE).assertDoesNotExist()
        compose.onNodeWithTag(UiTags.ACCOUNT_ADD_TRANSACTION).assertIsDisplayed()
    }

    @Test
    fun `the balance form starts from the balance in euros and keeps the sign`() {
        org.junit.Assert.assertEquals("1250,00", balanceInput(125000))
        org.junit.Assert.assertEquals("-12,05", balanceInput(-1205))
        org.junit.Assert.assertEquals("0,07", balanceInput(7))
    }

    @Test
    fun `each dot is described by the account's full label`() {
        show(ui(timeline()))

        compose.onNodeWithContentDescription("Boursorama · EUR · Compte courant").assertIsDisplayed()
        compose.onNodeWithContentDescription("EUR · Livret").assertIsDisplayed()
    }
}

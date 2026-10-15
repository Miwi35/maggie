package com.maggie.app.ui.screens.finance

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.Qualification
import com.maggie.app.data.model.QualificationKind
import com.maggie.app.data.model.Transaction
import com.maggie.app.screentest.FakeTransactionTransfers
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.screentest.Seed
import com.maggie.app.ui.UiTags
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The one sheet of a transaction (MAG-464): six ordered sections for an ordinary line, nature
 * and note only for the others, the pills of the list, the note and the filter drafted from the line.
 */
@RunWith(AndroidJUnit4::class)
class TransactionSheetScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private val sections = listOf("nature", "classement", "recurrence", "partage", "jugement", "note")

    private val coffee = Transaction(id = "tx-coffee", label = "Café du coin", amountCents = -450, bookedAt = "2026-09-14")

    private fun show(server: FakeTransactionTransfers) = compose.setContent {
        TransactionListScreen(viewModel = server.viewModel, accountName = "Livret", onBack = {})
    }

    private fun open(label: String) {
        compose.onNodeWithText(label).performClick()
        compose.waitForIdle()
    }

    private fun sectionTagsDrawn() =
        sections.filter { compose.onAllNodesWithTag(UiTags.sheetSection(it)).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun `an ordinary line opens a sheet with the six sections in order`() {
        show(FakeTransactionTransfers(transactions = listOf(coffee)))

        open("Café du coin")

        assertEquals(sections, sectionTagsDrawn())
        compose.onNodeWithTag(UiTags.sheetSection("partage")).assertExists()
        compose.onNodeWithTag(UiTags.sheetSection("jugement")).assertExists()
    }

    @Test
    @Config(qualifiers = "w1200dp-h900dp")
    fun `the sheet draws the same six sections on a tablet`() {
        show(FakeTransactionTransfers(transactions = listOf(coffee)))

        open("Café du coin")

        assertEquals(sections, sectionTagsDrawn())
    }

    @Test
    fun `an internal transfer keeps only its nature and its note, and releasing it brings the rest back`() {
        show(FakeTransactionTransfers())

        open("Virement vers Courant")

        assertEquals(listOf("nature", "note"), sectionTagsDrawn())
        compose.onAllNodesWithTag(UiTags.TRANSACTION_CATEGORY).assertCountEquals(0)
        compose.onAllNodesWithTag(UiTags.RECURRING_FROM_TRANSACTION).assertCountEquals(0)

        compose.onNodeWithTag(UiTags.TRANSFER_TOGGLE).performClick()
        compose.waitForIdle()

        assertEquals(sections, sectionTagsDrawn())
    }

    private fun openFirstIncident(server: FakeTransactionTransfers) {
        show(server)
        compose.onNodeWithTag(UiTags.INCIDENTS_TAB).performClick()
        compose.onNodeWithTag(UiTags.INCIDENT_ROW).performClick()
        compose.waitForIdle()
    }

    @Test
    fun `an incident whose credit is known reads Régularisé and keeps only nature and note`() {
        openFirstIncident(FakeTransactionTransfers(transactions = listOf(Seed.rejectedDebit, Seed.rejectedCredit, Seed.groceries)))

        assertEquals(listOf("nature", "note"), sectionTagsDrawn())
        compose.onNodeWithText("Régularisé").assertExists()
    }

    @Test
    fun `an incident with no known credit reads Non régularisé`() {
        openFirstIncident(FakeTransactionTransfers(transactions = listOf(Seed.rejectedDebit, Seed.groceries)))

        compose.onNodeWithText("Non régularisé").assertExists()
    }

    @Test
    fun `the list wears one row of pills, À trier on a line with no category`() {
        show(FakeTransactionTransfers(transactions = listOf(Seed.transferOut, coffee)))

        compose.onAllNodesWithTag(UiTags.TRANSFER_BADGE).assertCountEquals(1)
        compose.onAllNodesWithTag(UiTags.PILL_TO_SORT).assertCountEquals(1)
        compose.onAllNodesWithTag(UiTags.PILL_SERIES).assertCountEquals(0)
        compose.onAllNodesWithTag(UiTags.PILL_SHARED).assertCountEquals(0)
    }

    @Test
    fun `touching a pill opens the line's sheet`() {
        show(FakeTransactionTransfers(transactions = listOf(coffee)))

        compose.onNodeWithTag(UiTags.PILL_TO_SORT).performClick()
        compose.waitForIdle()

        assertEquals(sections, sectionTagsDrawn())
    }

    @Test
    fun `a line attached to a series wears the series pill, and the sheet detaches it`() {
        val attached = coffee.copy(
            category = "/api/categories/c1",
            recurringOperation = "/api/recurring_operations/s1",
            recurringOccurrenceOn = "2026-09-14",
        )
        val server = FakeTransactionTransfers(transactions = listOf(attached))
        show(server)
        compose.onAllNodesWithTag(UiTags.PILL_SERIES).assertCountEquals(1)

        open("Café du coin")
        compose.onNodeWithTag(UiTags.RECURRING_ATTACHED).performScrollTo().assertTextContains("Série", substring = true)
        compose.onNodeWithText("Échéance : 2026-09-14").assertExists()

        compose.onNodeWithTag(UiTags.RECURRING_DETACH).performScrollTo().performClick()
        compose.waitForIdle()

        assertEquals(listOf("tx-coffee"), server.detached)
        compose.onNodeWithTag(UiTags.RECURRING_DETACH).assertDoesNotExist()
        compose.onNodeWithText("Aucune série").assertExists()
    }

    @Test
    fun `the note is saved by its button and the button waits for a change`() {
        val server = FakeTransactionTransfers(transactions = listOf(coffee))
        show(server)
        open("Café du coin")

        compose.onNodeWithTag(UiTags.TRANSACTION_NOTE_SAVE).performScrollTo().assertIsNotEnabled()

        compose.onNodeWithTag(UiTags.TRANSACTION_NOTE).performScrollTo().performTextInput("Pour Léa")
        compose.onNodeWithTag(UiTags.TRANSACTION_NOTE_SAVE).performScrollTo().assertIsEnabled().performClick()
        compose.waitForIdle()

        assertEquals(listOf("Pour Léa"), server.notes)
        compose.onNodeWithTag(UiTags.TRANSACTION_NOTE_SAVE).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `the filter opens filled from the line and lists the lines it catches`() {
        val server = FakeTransactionTransfers(
            transactions = listOf(coffee, coffee.copy(id = "tx-coffee-2", label = "Café du coin Rennes"), Seed.groceries),
        )
        show(server)
        open("Café du coin")

        compose.onNodeWithTag(UiTags.RULE_DRAFT_OPEN).performScrollTo().performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Créer un filtre").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.RULE_DRAFT_PATTERN).assertTextContains("Café du coin")
        compose.onNodeWithText("Sens : Dépense").assertIsDisplayed()
        compose.onAllNodesWithTag(UiTags.RULE_DRAFT_MATCH).assertCountEquals(2)
        compose.onNodeWithTag(UiTags.RULE_DRAFT_SAVE).assertIsNotEnabled()
    }

    @Test
    fun `the filter back button returns to the sheet`() {
        show(FakeTransactionTransfers(transactions = listOf(coffee)))
        open("Café du coin")
        compose.onNodeWithTag(UiTags.RULE_DRAFT_OPEN).performScrollTo().performClick()
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Retour").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(UiTags.sheetSection("classement")).assertExists()
    }

    @Test
    fun `the classement shows no provenance for a line nobody qualified`() {
        show(FakeTransactionTransfers(transactions = listOf(coffee)))

        open("Café du coin")

        compose.onNodeWithTag(UiTags.QUALIFICATION_MANDATORY).performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithTag(UiTags.QUALIFICATION_PROVENANCE).assertCountEquals(0)
    }

    @Test
    fun `a line set by a series says which one, and a hand qualification says À la main`() {
        val server = FakeTransactionTransfers(
            transactions = listOf(coffee.copy(qualifiedBy = "series", qualifiedBySeries = "/api/recurring_operations/s1")),
        )
        show(server)
        open("Café du coin")

        compose.onNodeWithTag(UiTags.QUALIFICATION_PROVENANCE).performScrollTo().assertTextEquals("Posée par une série")

        compose.onNodeWithTag(UiTags.QUALIFICATION_MANDATORY).performScrollTo().performClick()
        compose.waitForIdle()

        assertEquals(listOf<Pair<String?, Qualification>>(null to Qualification(true, QualificationKind.None)), server.qualified)
        compose.onNodeWithTag(UiTags.QUALIFICATION_PROVENANCE).performScrollTo().assertTextEquals("À la main")
    }

    @Test
    fun `choosing a type sends it with the whole qualification`() {
        val server = FakeTransactionTransfers(transactions = listOf(coffee))
        show(server)
        open("Café du coin")

        compose.onNodeWithTag(UiTags.qualificationKind("Repayment")).performScrollTo().performClick()
        compose.waitForIdle()

        assertEquals(listOf<Pair<String?, Qualification>>(null to Qualification(false, QualificationKind.Repayment)), server.qualified)
    }
}

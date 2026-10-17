package com.maggie.app.ui.screens.finance

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.Contact
import com.maggie.app.data.model.MyShareRequestShare
import com.maggie.app.data.model.Transaction
import com.maggie.app.data.model.formatCents
import com.maggie.app.screentest.FakeTransactionTransfers
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The sheet's « Partage » as it is drawn (MAG-451), and the tags `19-finance-contacts` addresses
 * it by: my part of the gîte, the people who owe the rest picked in the full-screen picker, the
 * pill the line wears once shared. `TransactionViewModelTest` says what each gesture does to the state.
 */
@RunWith(AndroidJUnit4::class)
class TransactionShareScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private val gite = Transaction(id = "tx-gite", label = "GITE DES CIMES", amountCents = -80000, bookedAt = "2026-10-10")
    private val salary = Transaction(id = "tx-salary", label = "SALAIRE", amountCents = 250000, bookedAt = "2026-10-01")
    private val people = listOf(Contact("c-julie", "Julie"), Contact("c-tom", "Tom"), Contact("c-ines", "Inès"))

    private fun show(server: FakeTransactionTransfers) = compose.setContent {
        TransactionListScreen(viewModel = server.viewModel, accountName = "Courant", onBack = {})
    }

    private fun open(label: String) {
        compose.onNodeWithText(label).performClick()
        compose.waitForIdle()
    }

    private fun addPerson(name: String) {
        compose.onNodeWithTag(UiTags.SHARE_ADD_PERSON).performScrollTo().performClick()
        compose.onNodeWithTag(UiTags.CONTACT_PICKER_SEARCH).performTextInput(name)
        compose.onNodeWithTag(UiTags.CONTACT_PICKER_RESULT).performClick()
        compose.waitForIdle()
    }

    @Test
    fun `my part of 200 € of an 800 € gîte, owed 200 € each by Julie, Tom and Inès`() {
        val server = FakeTransactionTransfers(transactions = listOf(gite), contacts = people)
        show(server)
        open("GITE DES CIMES")

        compose.onNodeWithTag(UiTags.SHARE_SWITCH).performScrollTo().performClick()
        compose.onNodeWithTag(UiTags.SHARE_MINE).performScrollTo().performTextReplacement("200")
        listOf("Julie", "Tom", "Inès").forEach(::addPerson)

        compose.onNodeWithTag(UiTags.SHARE_OWED).assertTextEquals("On te doit ${formatCents(60000)}")
        compose.onAllNodesWithTag(UiTags.SHARE_PERSON).assertCountEquals(3)
        compose.onNodeWithTag(UiTags.SHARE_SAVE).performScrollTo().assertIsEnabled().performClick()
        compose.waitForIdle()

        val sent = server.sharesSent.single()
        assertEquals(20000, sent.myShareCents)
        // An equal split names no amount: the server makes it, the last cent on the last.
        assertEquals(listOf("c-julie", "c-tom", "c-ines").map { MyShareRequestShare(it) }, sent.shares)
        compose.onNodeWithText("Partage enregistré").performScrollTo()
    }

    @Test
    fun `the − and + step my part by one euro`() {
        show(FakeTransactionTransfers(transactions = listOf(gite), contacts = people))
        open("GITE DES CIMES")
        compose.onNodeWithTag(UiTags.SHARE_SWITCH).performScrollTo().performClick()

        compose.onNodeWithTag(UiTags.SHARE_MINE_MORE).performScrollTo().performClick()
        compose.onNodeWithTag(UiTags.SHARE_OWED).assertTextEquals("On te doit ${formatCents(39900)}")

        compose.onNodeWithTag(UiTags.SHARE_MINE_LESS).performClick()
        compose.onNodeWithTag(UiTags.SHARE_MINE_LESS).performClick()
        compose.onNodeWithTag(UiTags.SHARE_OWED).assertTextEquals("On te doit ${formatCents(40100)}")
    }

    @Test
    fun `nobody to owe the rest yet, « Enregistrer » waits and says why`() {
        show(FakeTransactionTransfers(transactions = listOf(gite), contacts = people))
        open("GITE DES CIMES")

        compose.onNodeWithTag(UiTags.SHARE_SWITCH).performScrollTo().performClick()

        compose.onNodeWithTag(UiTags.SHARE_PROBLEM).performScrollTo()
            .assertTextEquals("Ajoute au moins une personne qui te doit sa part.")
        compose.onNodeWithTag(UiTags.SHARE_SAVE).assertIsNotEnabled()
    }

    @Test
    fun `a shared line wears « Partagée » in the list, and switching off takes it back`() {
        val server = FakeTransactionTransfers(transactions = listOf(gite.copy(myShareCents = 20000)), contacts = people)
        show(server)

        compose.onAllNodesWithTag(UiTags.PILL_SHARED).assertCountEquals(1)
        open("GITE DES CIMES")
        compose.onNodeWithTag(UiTags.SHARE_SWITCH).performScrollTo().performClick()
        compose.waitForIdle()

        assertEquals(listOf("tx-gite"), server.sharesRemoved)
        compose.onNodeWithText("Partage retiré").performScrollTo()
    }

    @Test
    fun `an income is not shared, it is a recette or to be given back`() {
        show(FakeTransactionTransfers(transactions = listOf(salary)))
        open("SALAIRE")

        compose.onNodeWithText("Ce virement est").performScrollTo()
        compose.onNodeWithTag(UiTags.TRANSACTION_DEBT_RECEIPT).assertExists()
        compose.onNodeWithTag(UiTags.TRANSACTION_DEBT_TOGGLE).assertExists()
        compose.onAllNodesWithTag(UiTags.SHARE_SWITCH).assertCountEquals(0)
        // A recette until « À rembourser » is chosen: nothing more to fill in.
        compose.onAllNodesWithTag(UiTags.TRANSACTION_DEBT_SAVE).assertCountEquals(0)
    }

    @Test
    fun `an income given back to Tom for 100 € is saved from the full-screen picker`() {
        show(FakeTransactionTransfers(transactions = listOf(salary), contacts = people))
        open("SALAIRE")

        compose.onNodeWithTag(UiTags.TRANSACTION_DEBT_TOGGLE).performScrollTo().performClick()
        compose.onNodeWithTag(UiTags.TRANSACTION_DEBT_SAVE).performScrollTo().assertIsNotEnabled()

        compose.onNodeWithTag(UiTags.TRANSACTION_DEBT_PERSON).performScrollTo().performClick()
        compose.onNodeWithTag(UiTags.CONTACT_PICKER_SEARCH).performTextInput("Tom")
        compose.onNodeWithTag(UiTags.CONTACT_PICKER_RESULT).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(UiTags.TRANSACTION_DEBT_AMOUNT).performScrollTo().performTextReplacement("100")
        compose.onNodeWithTag(UiTags.TRANSACTION_DEBT_SUMMARY).assertTextEquals(
            "Crée une dette de ${formatCents(10000)} envers Tom, dans « Tu dois »",
        )

        compose.onNodeWithTag(UiTags.TRANSACTION_DEBT_SAVE).performScrollTo().assertIsEnabled().performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Dette enregistrée").performScrollTo()
    }

    @Test
    @Config(qualifiers = "w1200dp-h900dp")
    fun `the tablet draws the same « Partage »`() {
        val server = FakeTransactionTransfers(transactions = listOf(gite), contacts = people)
        show(server)
        open("GITE DES CIMES")

        compose.onNodeWithTag(UiTags.SHARE_SWITCH).performScrollTo().performClick()
        addPerson("Tom")

        compose.onAllNodesWithTag(UiTags.SHARE_PERSON).assertCountEquals(1)
        assertTrue(compose.onAllNodesWithTag(UiTags.SHARE_SAVE).fetchSemanticsNodes().isNotEmpty())
    }
}

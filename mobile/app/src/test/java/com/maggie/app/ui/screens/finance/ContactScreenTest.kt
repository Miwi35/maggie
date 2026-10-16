package com.maggie.app.ui.screens.finance

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.Contact
import com.maggie.app.data.model.ContactCounterparty
import com.maggie.app.data.model.CounterpartySuggestion
import com.maggie.app.data.model.PeerDebt
import com.maggie.app.data.model.PeerDebtShare
import com.maggie.app.data.model.owesYouLabel
import com.maggie.app.data.repository.ContactRepository
import com.maggie.app.data.repository.PeerDebtRepository
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The contacts and a contact's page as they are drawn, and the tags `19-finance-contacts`
 * addresses them by (MAG-450). `ContactViewModelTest` says what each gesture does to the state.
 */
@RunWith(AndroidJUnit4::class)
class ContactScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private val tom = Contact(id = "c-tom", name = "Tom")
    private val contacts = mockk<ContactRepository>()
    private val peerDebts = mockk<PeerDebtRepository>()

    private fun viewModel(list: List<Contact> = listOf(tom), debts: List<PeerDebt> = emptyList()): ContactViewModel {
        coEvery { contacts.getContacts() } returns Result.success(list)
        coEvery { peerDebts.getPeerDebts() } returns Result.success(debts)
        coEvery { contacts.getCounterparties("c-tom") } returns Result.success(
            listOf(
                ContactCounterparty(
                    id = "cp-1",
                    contact = "/api/contacts/c-tom",
                    label = "VIR. DE TOM MARTIN",
                    transactionCount = 2,
                    lastSeenOn = "2026-10-12",
                ),
            ),
        )
        coEvery { contacts.getSuggestions("c-tom") } returns Result.success(
            listOf(CounterpartySuggestion(label = "LYDIA TOM M", transactionCount = 1, lastSeenOn = "2026-10-01")),
        )
        return ContactViewModel(contacts, peerDebts)
    }

    @Test
    fun `the page lists their labels with the count and the last day, and what may be theirs too`() {
        val model = viewModel()
        compose.setContent { ContactScreen(viewModel = model, onBack = {}) }

        compose.onNodeWithText("Tom").performClick()

        compose.onNodeWithText("Ses comptes").assertIsDisplayed()
        compose.onNodeWithText("VIR. DE TOM MARTIN").assertIsDisplayed()
        compose.onNodeWithText("2 virements").assertIsDisplayed()
        compose.onNodeWithText("· dernier le 12 octobre 2026").assertIsDisplayed()
        compose.onNodeWithText("Peut-être aussi Tom :").assertIsDisplayed()
        compose.onNodeWithText("LYDIA TOM M").assertIsDisplayed()
    }

    @Test
    fun `« En cours » lists what they still owe, its object, day and « te doit »`() {
        val gite = PeerDebt(
            id = "d-1",
            `object` = "Gîte des Cimes",
            createdAt = "2026-10-10T09:00:00+00:00",
            amountCents = 60000,
            remainingCents = 60000,
            shares = listOf(PeerDebtShare(id = "s-1", contactId = "c-tom", contactName = "Tom", amountCents = 20000, remainingCents = 20000)),
        )
        val model = viewModel(debts = listOf(gite))
        compose.setContent { ContactScreen(viewModel = model, onBack = {}) }

        compose.onNodeWithText("Tom").performClick()

        compose.onNodeWithText("En cours").assertIsDisplayed()
        compose.onNodeWithText("Gîte des Cimes").assertIsDisplayed()
        compose.onNodeWithText("10 octobre 2026").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.CONTACT_DEBT_REMAINING).assertTextEquals(owesYouLabel(20000))
    }

    @Test
    fun `nothing owed says so`() {
        val model = viewModel()
        compose.setContent { ContactScreen(viewModel = model, onBack = {}) }

        compose.onNodeWithText("Tom").performClick()

        compose.onNodeWithText("Tom ne te doit rien pour l'instant.").assertIsDisplayed()
    }

    @Test
    fun `the « + » of a suggestion links it in one gesture`() {
        coEvery { contacts.link("c-tom", "LYDIA TOM M") } returns
            Result.success(ContactCounterparty(id = "cp-2", contact = "/api/contacts/c-tom", label = "LYDIA TOM M"))
        val model = viewModel()
        compose.setContent { ContactScreen(viewModel = model, onBack = {}) }

        compose.onNodeWithText("Tom").performClick()
        compose.onNodeWithTag(UiTags.CONTACT_SUGGESTION_ADD).performClick()
        compose.waitForIdle()

        coVerify(exactly = 1) { contacts.link("c-tom", "LYDIA TOM M") }
    }

    @Test
    fun `« Retirer » unlinks the label`() {
        coEvery { contacts.unlink("cp-1") } returns Result.success(Unit)
        val model = viewModel()
        compose.setContent { ContactScreen(viewModel = model, onBack = {}) }

        compose.onNodeWithText("Tom").performClick()
        compose.onNodeWithTag(UiTags.CONTACT_COUNTERPARTY_REMOVE).performClick()
        compose.waitForIdle()

        coVerify(exactly = 1) { contacts.unlink("cp-1") }
    }

    @Test
    fun `a name nobody bears is offered for creation from the search`() {
        coEvery { contacts.createContact("Léo") } returns Result.success(Contact("c-leo", "Léo"))
        coEvery { contacts.getCounterparties("c-leo") } returns Result.success(emptyList())
        coEvery { contacts.getSuggestions("c-leo") } returns Result.success(emptyList())
        val model = viewModel()
        compose.setContent { ContactScreen(viewModel = model, onBack = {}) }

        compose.onNodeWithTag(UiTags.CONTACT_SEARCH).performTextInput("Léo")
        compose.onNodeWithText("+ Créer « Léo »").performClick()
        compose.waitForIdle()

        coVerify(exactly = 1) { contacts.createContact("Léo") }
        compose.onNodeWithText("Ses comptes").assertIsDisplayed()
    }

    @Test
    fun `no contact yet says so`() {
        val model = viewModel(list = emptyList())
        compose.setContent { ContactScreen(viewModel = model, onBack = {}) }

        compose.onNodeWithTag(UiTags.CONTACT_EMPTY).assertIsDisplayed()
        compose.onNodeWithText("Aucun contact").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun `on a tablet the page sits beside the list`() {
        val model = viewModel()
        compose.setContent { ContactScreen(viewModel = model, onBack = {}, showsDetailPane = true) }

        compose.onNodeWithText("Touchez un contact pour voir sa fiche ici.").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.CONTACT_ROW).performClick()

        compose.onNodeWithTag(UiTags.CONTACT_SEARCH).assertIsDisplayed()
        compose.onNodeWithText("VIR. DE TOM MARTIN").assertIsDisplayed()
    }
}

package com.maggie.app.ui.screens.finance

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.Category
import com.maggie.app.data.model.Contact
import com.maggie.app.data.model.ContactCounterparty
import com.maggie.app.data.model.ContactRef
import com.maggie.app.data.model.SortResult
import com.maggie.app.data.model.SortSuggestion
import com.maggie.app.data.model.TO_SORT_REASON_PEER_TRANSFER
import com.maggie.app.data.model.TransactionAllocation
import com.maggie.app.data.model.ToSortPile
import com.maggie.app.data.model.TransactionToSort
import com.maggie.app.data.model.RETROSPECT_AVOIDABLE
import com.maggie.app.data.model.RETROSPECT_KEEP
import com.maggie.app.data.repository.CategorizationRuleRepository
import com.maggie.app.data.repository.CategoryRepository
import com.maggie.app.data.repository.ContactRepository
import com.maggie.app.data.repository.PeerDebtRepository
import com.maggie.app.data.repository.TransactionRepository
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The « À qualifier » card as it is drawn, and the tags `13-finance-to-sort` addresses it by (MAG-423, MAG-458).
 *
 * `ToSortViewModelTest` says what each answer does to the state; this one says that the
 * suggestion and its reason reach the screen, that the buttons send what the card shows,
 * and that « Autre catégorie » offers the whole path of a heading, three levels deep.
 */
@RunWith(AndroidJUnit4::class)
class ToSortScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private val carrefour = TransactionToSort(
        id = "tx-1",
        label = "CARREFOUR MARKET 1234",
        amountCents = -5320,
        bookedAt = "2026-10-12",
        accountName = "Courant",
        suggestion = SortSuggestion("cat-courses", "Courses", "rule", "Règle « contient CARREFOUR »"),
    )

    private val categories = listOf(
        Category(id = "cat-courses", name = "Courses"),
        Category(id = "cat-loisirs", name = "Loisirs"),
        Category(id = "cat-bars", name = "Bars", parent = "/api/categories/cat-loisirs"),
        Category(id = "cat-obsydian", name = "Obsydian", parent = "/api/categories/cat-bars"),
    )

    private val transactions = mockk<TransactionRepository>()
    private val contactRepository = mockk<ContactRepository>()
    private val peerDebts = mockk<PeerDebtRepository>()

    private fun viewModel(pile: List<TransactionToSort> = listOf(carrefour)): ToSortViewModel {
        coEvery { transactions.setRetrospect(any(), any()) } returns Result.success(Unit)
        val categoryRepository = mockk<CategoryRepository>()
        coEvery { categoryRepository.getCategories() } returns Result.success(categories)
        coEvery { transactions.getToSort(oldestFirst = true) } returns Result.success(ToSortPile(pile.size, pile))
        coEvery { transactions.sortToCategory(any(), any(), any()) } returns Result.success(SortResult(success = true))

        return ToSortViewModel(transactions, categoryRepository, mockk<CategorizationRuleRepository>(relaxed = true), contactRepository, peerDebts)
    }

    // « À qualifier » opens on the list (MAG-507): a line is tapped to bring its card up.
    private fun showCard(
        model: ToSortViewModel,
        row: Int = 0,
        onOpenTransaction: (TransactionToSort) -> Unit = {},
    ) {
        compose.setContent { ToSortScreen(viewModel = model, onBack = {}, onOpenTransaction = onOpenTransaction) }
        compose.onAllNodesWithTag(UiTags.TO_SORT_ROW)[row].performClick()
        compose.waitForIdle()
    }

    private val pileOfThree = listOf(
        carrefour,
        carrefour.copy(id = "tx-2", label = "BOULANGERIE PAUL", amountCents = -450, bookedAt = "2026-10-13", suggestion = null),
        carrefour.copy(id = "tx-3", label = "SNCF INTERNET", amountCents = -3900, bookedAt = "2026-10-14"),
    )

    @Test
    fun `the screen opens on the list, with the count, the date, the amount and the pill of each line`() {
        val model = viewModel(pile = pileOfThree)
        compose.setContent { ToSortScreen(viewModel = model, onBack = {}) }

        compose.onNodeWithTag(UiTags.TO_SORT_CARD).assertDoesNotExist()
        compose.onNodeWithText("3 à qualifier").assertIsDisplayed()
        compose.onAllNodesWithTag(UiTags.TO_SORT_ROW).assertCountEquals(3)
        compose.onNodeWithText("BOULANGERIE PAUL").assertIsDisplayed()
        // The top bar's title and the pill of the one line without a suggestion.
        compose.onAllNodesWithText("À qualifier").assertCountEquals(2)
        compose.onAllNodesWithText("→ Courses").assertCountEquals(2)
    }

    @Test
    fun `opening a line shows its card, Suivant and Précédent walk the list, back returns to it`() {
        val model = viewModel(pile = pileOfThree)
        showCard(model, row = 1)

        compose.onNodeWithText("BOULANGERIE PAUL").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.REVIEW_POSITION).assertTextEquals("2 / 3")

        compose.onNodeWithTag(UiTags.REVIEW_NEXT).performClick()
        compose.onNodeWithText("SNCF INTERNET").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.REVIEW_NEXT).assertIsNotEnabled()

        compose.onNodeWithTag(UiTags.REVIEW_PREVIOUS).performClick()
        compose.onNodeWithTag(UiTags.REVIEW_PREVIOUS).performClick()
        compose.onNodeWithText("CARREFOUR MARKET 1234").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.REVIEW_PREVIOUS).assertIsNotEnabled()

        compose.onNodeWithTag(UiTags.REVIEW_BACK_TO_LIST).performClick()
        compose.onNodeWithTag(UiTags.TO_SORT_CARD).assertDoesNotExist()
        compose.onAllNodesWithTag(UiTags.TO_SORT_ROW).assertCountEquals(3)
    }

    @Test
    fun `a swipe to the left opens the next line and a swipe to the right the previous one`() {
        val model = viewModel(pile = pileOfThree)
        showCard(model, row = 1)

        compose.onNodeWithTag(UiTags.TO_SORT_CARD).performTouchInput { swipeLeft() }
        compose.onNodeWithText("SNCF INTERNET").assertIsDisplayed()

        compose.onNodeWithTag(UiTags.TO_SORT_CARD).performTouchInput { swipeRight() }
        compose.onNodeWithText("BOULANGERIE PAUL").assertIsDisplayed()
    }

    @Test
    fun `qualifying from the card brings the next line, not the list`() {
        val model = viewModel(pile = pileOfThree)
        showCard(model)
        // The server's next read no longer has the line.
        coEvery { transactions.getToSort(oldestFirst = true) } returns Result.success(ToSortPile(2, pileOfThree.drop(1)))

        compose.onNodeWithTag(UiTags.TO_SORT_VALIDATE).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(UiTags.TO_SORT_CARD).assertIsDisplayed()
        compose.onNodeWithText("BOULANGERIE PAUL").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.REVIEW_POSITION).assertTextEquals("1 / 2")
    }

    @Test
    fun `the card says where the line would go and why`() {
        val model = viewModel()
        showCard(model)

        compose.onNodeWithTag(UiTags.TO_SORT_CARD).assertIsDisplayed()
        compose.onNodeWithText("CARREFOUR MARKET 1234").assertIsDisplayed()
        compose.onNodeWithText("→ Courses").assertIsDisplayed()
        compose.onNodeWithText("Règle « contient CARREFOUR »").assertIsDisplayed()
    }

    @Test
    fun `validating with the switch on asks for the rule as well`() {
        val model = viewModel()
        showCard(model)

        compose.onNodeWithTag(UiTags.TO_SORT_ALWAYS).performClick()
        compose.onNodeWithTag(UiTags.TO_SORT_VALIDATE).performClick()
        compose.waitForIdle()

        coVerify(exactly = 1) { transactions.sortToCategory("tx-1", "cat-courses", true) }
    }

    @Test
    fun `another category is found by its whole path`() {
        val model = viewModel()
        showCard(model)

        compose.onNodeWithTag(UiTags.TO_SORT_OTHER).performClick()
        compose.onNodeWithText("Loisirs › Bars › Obsydian").assertIsDisplayed()
        compose.onNodeWithText("Loisirs › Bars › Obsydian").performClick()
        compose.waitForIdle()

        coVerify(exactly = 1) { transactions.sortToCategory("tx-1", "cat-obsydian", false) }
    }

    @Test
    fun `an empty pile says so`() {
        val model = viewModel(pile = emptyList())
        compose.setContent { ToSortScreen(viewModel = model, onBack = {}) }

        compose.onNodeWithTag(UiTags.TO_SORT_EMPTY).assertIsDisplayed()
        compose.onNodeWithText("Rien à qualifier").assertIsDisplayed()
    }

    @Test
    fun `the satisfaction is offered on a spend that can be judged, and sends the verdict`() {
        val judgeable = carrefour.copy(judgeable = true)
        val model = viewModel(pile = listOf(judgeable))
        showCard(model)

        compose.onNodeWithText("À conserver").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(UiTags.TO_SORT_AVOIDABLE).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(UiTags.TO_SORT_KEEP).performScrollTo().performClick()
        compose.waitForIdle()

        coVerify(exactly = 1) { transactions.setRetrospect("tx-1", RETROSPECT_AVOIDABLE) }
        coVerify(exactly = 1) { transactions.setRetrospect("tx-1", RETROSPECT_KEEP) }
    }

    @Test
    fun `a line that cannot be judged offers no satisfaction at all`() {
        val model = viewModel(pile = listOf(carrefour.copy(judgeable = false)))
        showCard(model)

        compose.onNodeWithTag(UiTags.TO_SORT_CARD).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.TO_SORT_KEEP).assertDoesNotExist()
        compose.onNodeWithTag(UiTags.TO_SORT_AVOIDABLE).assertDoesNotExist()
        compose.onNodeWithText("À conserver").assertDoesNotExist()
        compose.onNodeWithText("Superflue").assertDoesNotExist()
    }

    @Test
    fun `the card carries the sheet's gestures, and the sheet opens from it`() {
        val opened = mutableListOf<String>()
        val model = viewModel()
        showCard(model, onOpenTransaction = { opened += it.id })

        compose.onNodeWithTag(UiTags.TO_SORT_QUALIFICATION).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(UiTags.TO_SORT_RULE).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Créer un filtre").assertExists()
        compose.onNodeWithTag(UiTags.TO_SORT_OPEN_SHEET).performScrollTo().performClick()

        assertEquals(listOf("tx-1"), opened)
    }

    @Test
    fun `the qualification unfolds from the card`() {
        val model = viewModel()
        showCard(model)

        compose.onNodeWithText("Obligatoire").assertDoesNotExist()
        compose.onNodeWithTag(UiTags.TO_SORT_QUALIFICATION).performScrollTo().performClick()

        compose.onNodeWithText("Obligatoire").assertExists()
    }

    private val tomTransfer = TransactionToSort(
        id = "tx-3",
        label = "VIR. DE TOM MARTIN",
        amountCents = 40000,
        bookedAt = "2026-10-13",
        asksForContact = true,
        counterpartyLabel = "VIR. DE TOM MARTIN",
    )

    @Test
    fun `a transfer nobody claims offers a contact, created in the full-screen picker`() {
        coEvery { contactRepository.getContacts() } returns Result.success(listOf(Contact("c-anna", "Anna")))
        coEvery { contactRepository.createContact("Tom") } returns Result.success(Contact("c-tom", "Tom"))
        coEvery { contactRepository.link("c-tom", "VIR. DE TOM MARTIN") } returns
            Result.success(ContactCounterparty(id = "cp-1", contact = "/api/contacts/c-tom", label = "VIR. DE TOM MARTIN"))
        val model = viewModel(pile = listOf(tomTransfer))
        showCard(model)

        compose.onNodeWithTag(UiTags.TO_SORT_CONTACT).performClick()
        compose.onNodeWithTag(UiTags.CONTACT_PICKER_SEARCH).performTextInput("Tom")
        compose.onNodeWithText("+ Créer « Tom »").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.CONTACT_PICKER_CREATE).performClick()
        compose.waitForIdle()

        coVerify(exactly = 1) { contactRepository.createContact("Tom") }
        coVerify(exactly = 1) { contactRepository.link("c-tom", "VIR. DE TOM MARTIN") }
    }

    @Test
    fun `a line attached to a contact says whose it is and asks nothing`() {
        val model = viewModel(pile = listOf(tomTransfer.copy(contact = ContactRef("c-tom", "Tom"), asksForContact = false)))
        showCard(model)

        compose.onNodeWithTag(UiTags.TO_SORT_CONTACT_NAME).assertIsDisplayed()
        compose.onNodeWithText("Contact : Tom").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.TO_SORT_CONTACT).assertDoesNotExist()
    }

    @Test
    fun `a purchase asks for no contact`() {
        val model = viewModel()
        showCard(model)

        compose.onNodeWithTag(UiTags.TO_SORT_CONTACT).assertDoesNotExist()
    }

    @Test
    fun `a filed transfer with a person offers neither Virement interne nor Créer un filtre`() {
        // The line is filed already: the API refuses an internal transfer on it, and a filter has nothing to file.
        val filed = tomTransfer.copy(
            contact = ContactRef("c-tom", "Tom"),
            asksForContact = false,
            reason = TO_SORT_REASON_PEER_TRANSFER,
            asksForPeerKind = true,
        )
        coEvery { peerDebts.getAllocation("tx-3") } returns
            Result.success(TransactionAllocation(transactionId = "tx-3", amountCents = 40000, canBeAllocated = true))
        val model = viewModel(pile = listOf(filed))
        showCard(model)

        compose.onAllNodesWithTag(UiTags.TO_SORT_TRANSFER).assertCountEquals(0)
        compose.onAllNodesWithTag(UiTags.TO_SORT_RULE).assertCountEquals(0)
        compose.onNodeWithTag(UiTags.TO_SORT_OPEN_SHEET).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a line still to file offers Virement interne`() {
        val model = viewModel()
        showCard(model)

        compose.onNodeWithTag(UiTags.TO_SORT_TRANSFER).performScrollTo().assertIsDisplayed()
    }
}

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
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.Category
import com.maggie.app.data.model.Contact
import com.maggie.app.data.model.ContactRef
import com.maggie.app.data.model.PeerAllocationCandidate
import com.maggie.app.data.model.PeerAllocationRequest
import com.maggie.app.data.model.PeerAllocationRequestLine
import com.maggie.app.data.model.PeerKind
import com.maggie.app.data.model.TO_SORT_REASON_PEER_TRANSFER
import com.maggie.app.data.model.ToSortPile
import com.maggie.app.data.model.Transaction
import com.maggie.app.data.model.TransactionAllocation
import com.maggie.app.data.model.TransactionToSort
import com.maggie.app.data.model.formatCents
import com.maggie.app.data.repository.CategorizationRuleRepository
import com.maggie.app.data.repository.CategoryRepository
import com.maggie.app.data.repository.ContactRepository
import com.maggie.app.data.repository.PeerDebtRepository
import com.maggie.app.data.repository.TransactionRepository
import com.maggie.app.screentest.FakeTransactionTransfers
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * « Ce virement est » as it is drawn (MAG-453), on the sheet — phone and tablet — and on the « À trier » card, with
 * the tags `19-finance-contacts` addresses it by. `TransactionViewModelTest` and `ToSortViewModelTest` say what each
 * gesture does to the state.
 */
@RunWith(AndroidJUnit4::class)
class PeerTransferQualifierScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private val tomTransfer = Transaction(id = "tx-tom", label = "VIR. DE TOM MARTIN", amountCents = 40000, bookedAt = "2026-10-10")

    private val gite = PeerAllocationCandidate(
        shareId = "share-gite", `object` = "Gîte de Crozon", contactId = "c-tom", contactName = "Tom",
        amountCents = 20000, remainingCents = 20000, availableCents = 20000, fromContact = true,
    )

    private val allocation = TransactionAllocation(
        transactionId = "tx-tom",
        amountCents = 40000,
        canBeAllocated = true,
        contact = ContactRef("c-tom", "Tom"),
        unallocatedCents = 40000,
        candidates = listOf(gite),
    )

    private fun showSheet(): FakeTransactionTransfers {
        val server = FakeTransactionTransfers(
            transactions = listOf(tomTransfer),
            contacts = listOf(Contact("c-tom", "Tom")),
            allocations = mapOf("tx-tom" to allocation),
        )
        compose.setContent { TransactionListScreen(viewModel = server.viewModel, accountName = "Courant", onBack = {}) }
        compose.onNodeWithText("VIR. DE TOM MARTIN").performClick()
        compose.waitForIdle()
        return server
    }

    private fun allocateToGite() {
        compose.onNodeWithTag(UiTags.PEER_KIND_REPAYMENT).performScrollTo().performClick()
        compose.onNodeWithTag(UiTags.PEER_SAVE).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Gîte de Crozon").performScrollTo().performClick()
        compose.onNodeWithTag(UiTags.PEER_UNALLOCATED).performScrollTo().assertTextEquals("Non affecté : ${formatCents(20000)}")
        compose.onNodeWithTag(UiTags.PEER_SAVE).performScrollTo().assertIsEnabled().performClick()
        compose.waitForIdle()
    }

    @Test
    fun `the sheet of Tom's 400 € allocates 200 € to the gîte, the rest Non affecté`() {
        val server = showSheet()

        compose.onNodeWithText("Ce virement est").performScrollTo()
        // The qualifier supersedes MAG-452's « Une recette » / « À rembourser ».
        compose.onAllNodesWithTag(UiTags.TRANSACTION_DEBT_TOGGLE).assertCountEquals(0)
        allocateToGite()

        assertEquals(
            listOf("tx-tom" to PeerAllocationRequest(PeerKind.REPAYMENT, allocations = listOf(PeerAllocationRequestLine("share-gite", 20000)))),
            server.allocationsSent,
        )
        compose.onNodeWithText("Remboursement enregistré").performScrollTo()
    }

    @Test
    fun `À rembourser asks for the person, proposing the transfer's contact`() {
        showSheet()

        compose.onNodeWithTag(UiTags.PEER_KIND_NEW_DEBT).performScrollTo().performClick()

        compose.onNodeWithTag(UiTags.PEER_PERSON).performScrollTo().assertTextEquals("Envers : Tom")
        compose.onNodeWithTag(UiTags.PEER_SUMMARY).performScrollTo()
            .assertTextEquals("Crée une dette de ${formatCents(40000)} envers Tom, dans « Tu dois »")
    }

    @Test
    @Config(qualifiers = "w1200dp-h900dp")
    fun `the tablet draws the same Ce virement est`() {
        val server = showSheet()

        allocateToGite()

        assertEquals(1, server.allocationsSent.size)
    }

    @Test
    fun `the À trier card asks Ce virement est and saves the allocation`() {
        val line = TransactionToSort(
            id = "tx-tom",
            label = "VIR. DE TOM MARTIN",
            amountCents = 40000,
            bookedAt = "2026-10-10",
            contact = ContactRef("c-tom", "Tom"),
            reason = TO_SORT_REASON_PEER_TRANSFER,
            asksForPeerKind = true,
        )
        val transactions = mockk<TransactionRepository>()
        val categories = mockk<CategoryRepository>()
        val peerDebts = mockk<PeerDebtRepository>()
        coEvery { categories.getCategories() } returns Result.success(listOf(Category(id = "cat-1", name = "Divers")))
        coEvery { transactions.getToSort(oldestFirst = true) } returnsMany listOf(
            Result.success(ToSortPile(1, listOf(line))),
            Result.success(ToSortPile(0, emptyList())),
        )
        coEvery { peerDebts.getAllocation("tx-tom") } returns Result.success(allocation)
        coEvery { peerDebts.setAllocation("tx-tom", any()) } returns Result.success(allocation.copy(peerKind = PeerKind.REPAYMENT))
        val model = ToSortViewModel(
            transactions, categories, mockk<CategorizationRuleRepository>(relaxed = true), mockk<ContactRepository>(), peerDebts,
        )
        compose.setContent { ToSortScreen(viewModel = model, onBack = {}) }
        compose.onAllNodesWithTag(UiTags.TO_SORT_ROW)[0].performClick()

        // Filed already: the card asks no category.
        compose.onAllNodesWithTag(UiTags.TO_SORT_OTHER).assertCountEquals(0)
        allocateToGite()

        coVerify {
            peerDebts.setAllocation("tx-tom", PeerAllocationRequest(PeerKind.REPAYMENT, allocations = listOf(PeerAllocationRequestLine("share-gite", 20000))))
        }
        compose.onNodeWithTag(UiTags.TO_SORT_EMPTY).assertExists()
    }

    private val noShareActions = ShareSheetActions(
        onEnable = {}, onMyShareChange = {}, onAddPerson = {}, onRemovePerson = {}, onAmountChange = { _, _ -> },
        onSubjectChange = {}, onNotesChange = {}, onSave = {},
    )
    private val noPeerActions = PeerTransferActions(
        onKindChange = {}, onToggleShare = {}, onShareAmountChange = { _, _ -> }, onPickPerson = {},
        onDebtAmountChange = {}, onSubjectChange = {}, onNotesChange = {}, onSave = {},
    )

    /** « Partage » of 60 € sent to Tom, whose share the server holds as [myShareCents], answered [peerKind]. */
    private fun showSentToTom(myShareCents: Int?, peerKind: String?) {
        val sent = Transaction(id = "tx-sent", label = "VIR. A TOM MARTIN", amountCents = -6000, bookedAt = "2026-10-10", myShareCents = myShareCents)
        val state = TransferDetailState(
            transaction = sent,
            share = ShareEditor(totalCents = 6000, canBeShared = true, savedMyShareCents = myShareCents, enabled = myShareCents != null),
            peer = PeerTransferEditor(
                transactionId = "tx-sent", totalCents = 6000, direction = PeerKind.OUTGOING, savedKind = peerKind,
                contactId = "c-tom", contactName = "Tom",
            ),
        )
        compose.setContent { ShareSection(state, noShareActions, peerActions = noPeerActions) }
    }

    @Test
    fun `money sent already shared keeps its Partage`() {
        showSentToTom(myShareCents = 2000, peerKind = null)

        compose.onNodeWithTag(UiTags.SHARE_SWITCH).assertExists()
        compose.onAllNodesWithTag(UiTags.peerKind(PeerKind.NEW_CLAIM)).assertCountEquals(0)
    }

    @Test
    fun `a Nouvelle créance, whose share the qualifier set to 0, still shows Ce virement est`() {
        showSentToTom(myShareCents = 0, peerKind = PeerKind.NEW_CLAIM)

        compose.onNodeWithTag(UiTags.peerKind(PeerKind.NEW_CLAIM)).assertExists()
        compose.onAllNodesWithTag(UiTags.SHARE_SWITCH).assertCountEquals(0)
    }
}

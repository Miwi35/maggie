package com.maggie.app.ui.screens.finance

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.ContactBalance
import com.maggie.app.data.model.PeerDebt
import com.maggie.app.data.model.PeerDebtShare
import com.maggie.app.data.model.PeerDebtSummary
import com.maggie.app.data.model.formatCents
import com.maggie.app.data.repository.PeerDebtRepository
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import io.mockk.coEvery
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** « Créances et dettes » as drawn (MAG-455): the tiles, the two tabs, a person's lines, and the way out of each. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h1400dp-xxhdpi")
class PeerDebtsScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private val peerDebts = mockk<PeerDebtRepository>()

    private val gite = PeerDebt(
        id = "d-gite",
        `object` = "Gîte de Crozon",
        notes = "Août",
        amountCents = 40000,
        remainingCents = 40000,
        shares = listOf(
            PeerDebtShare("s-1", "c-julie", "Julie", amountCents = 20000, remainingCents = 20000),
            PeerDebtShare("s-2", "c-tom", "Tom", amountCents = 20000, remainingCents = 20000),
        ),
    )
    private val petrol = PeerDebt(
        id = "d-petrol",
        direction = PeerDebt.DIRECTION_I_OWE,
        `object` = "Essence du road trip",
        remainingCents = 28000,
        shares = listOf(PeerDebtShare("s-3", "c-tom", "Tom", amountCents = 28000, remainingCents = 28000)),
    )

    private fun viewModel(
        summary: PeerDebtSummary = PeerDebtSummary(
            owedToMeCents = 40000,
            iOweCents = 28000,
            byContact = listOf(
                ContactBalance("c-julie", "Julie", netCents = 20000),
                ContactBalance("c-tom", "Tom", netCents = -8000, compensates = true),
            ),
        ),
        debts: List<PeerDebt> = listOf(gite, petrol),
    ): PeerDebtsViewModel {
        coEvery { peerDebts.getSummary() } returns Result.success(summary)
        coEvery { peerDebts.getOverview(status = "open") } returns Result.success(debts)
        return PeerDebtsViewModel(peerDebts)
    }

    @Test
    fun `the two totals are drawn, with what each covers`() {
        val model = viewModel()
        compose.setContent { PeerDebtsScreen(model, onBack = {}, onOpenContact = {}, onAddPerson = {}) }

        compose.onNodeWithTag(UiTags.PEER_DEBTS_TILE_OWED_TO_ME).assertIsDisplayed()
        compose.onNodeWithText(formatCents(40000)).assertIsDisplayed()
        compose.onNodeWithText("avancés sur ton matelas").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.PEER_DEBTS_TILE_I_OWE).assertIsDisplayed()
        compose.onNodeWithText(formatCents(28000)).assertIsDisplayed()
        compose.onNodeWithText("hors revenus et épargne").assertIsDisplayed()
    }

    @Test
    fun `by person, Tom shows what compensates and opens into a line per object and the way to his page`() {
        val opened = mutableListOf<String>()
        val model = viewModel()
        compose.setContent { PeerDebtsScreen(model, onBack = {}, onOpenContact = { opened += it }, onAddPerson = {}) }

        compose.onNodeWithText("Julie").assertIsDisplayed()
        compose.onNodeWithText("te doit ${formatCents(20000)}").assertIsDisplayed()
        compose.onNodeWithText("tu lui dois ${formatCents(8000)}").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.PEER_DEBTS_COMPENSATION)
            .assertIsDisplayed()

        compose.onNode(hasText("Tom") and androidx.compose.ui.test.hasClickAction()).performClick()

        assertEquals(2, compose.onAllNodesWithTag(UiTags.PEER_DEBTS_PERSON_LINE).fetchSemanticsNodes().size)
        compose.onNodeWithText("Essence du road trip").assertIsDisplayed()
        compose.onNodeWithText("tu lui dois ${formatCents(28000)}").assertIsDisplayed()
        compose.onNodeWithText("Fiche et comptes de Tom").performClick()

        assertEquals(listOf("c-tom"), opened)
    }

    @Test
    fun `by object, each shows its amount, who still owes and its note`() {
        val model = viewModel()
        compose.setContent { PeerDebtsScreen(model, onBack = {}, onOpenContact = {}, onAddPerson = {}) }

        compose.onNodeWithTag(UiTags.PEER_DEBTS_TAB_OBJECT).performClick()

        assertEquals(2, compose.onAllNodesWithTag(UiTags.PEER_DEBTS_OBJECT).fetchSemanticsNodes().size)
        compose.onNodeWithText("Gîte de Crozon").assertIsDisplayed()
        compose.onNodeWithText("on te doit ${formatCents(40000)}").assertIsDisplayed()
        compose.onNodeWithText("Julie ${formatCents(20000)} · Tom ${formatCents(20000)}").assertIsDisplayed()
        compose.onNodeWithText("Août").assertIsDisplayed()
        compose.onNodeWithText("tu dois ${formatCents(28000)}").assertIsDisplayed()
    }

    @Test
    fun `« + Personne » asks for the new-contact dialog`() {
        var added = 0
        val model = viewModel()
        compose.setContent { PeerDebtsScreen(model, onBack = {}, onOpenContact = {}, onAddPerson = { added++ }) }

        compose.onNodeWithTag(UiTags.PEER_DEBTS_ADD_PERSON).performClick()

        assertEquals(1, added)
    }

    @Test
    fun `nobody owing anything says so`() {
        val model = viewModel(summary = PeerDebtSummary(), debts = emptyList())
        compose.setContent { PeerDebtsScreen(model, onBack = {}, onOpenContact = {}, onAddPerson = {}) }

        compose.onNodeWithTag(UiTags.PEER_DEBTS_EMPTY).assertIsDisplayed()
        compose.onNodeWithText("Rien en cours").assertIsDisplayed()
    }

    @Test
    fun `a read that failed offers a retry that loads the screen`() {
        coEvery { peerDebts.getSummary() } returns Result.failure(RuntimeException("Hors ligne"))
        coEvery { peerDebts.getOverview(status = "open") } returns Result.success(listOf(gite))
        val model = PeerDebtsViewModel(peerDebts)
        compose.setContent { PeerDebtsScreen(model, onBack = {}, onOpenContact = {}, onAddPerson = {}) }

        compose.onNodeWithText("Hors ligne").assertIsDisplayed()
        coEvery { peerDebts.getSummary() } returns Result.success(PeerDebtSummary(owedToMeCents = 40000))
        compose.onNodeWithTag(UiTags.PEER_DEBTS_RETRY).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(UiTags.PEER_DEBTS_TILE_OWED_TO_ME).assertIsDisplayed()
    }
}

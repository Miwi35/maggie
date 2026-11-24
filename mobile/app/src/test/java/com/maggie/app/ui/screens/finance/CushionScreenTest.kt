package com.maggie.app.ui.screens.finance

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.CushionAccount
import com.maggie.app.data.model.CushionStatus
import com.maggie.app.data.model.formatCentsRounded
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.screentest.tap
import com.maggie.app.ui.UiTags
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Matelas screen, as the mock-up draws it (MAG-454, MAG-938). */
@RunWith(AndroidJUnit4::class)
class CushionScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private val lent = CushionStatus(
        state = "building",
        targetCents = 720000,
        criticalCents = 380000,
        currentCents = 466500,
        deficitCents = 253500,
        coveragePercent = 65,
        peerClaimsCents = 69000,
        peerClaimObjects = listOf("Gîte de Crozon", "Hellfest"),
        accounts = listOf(CushionAccount(id = "a1", name = "Livret A", bank = "Boursorama", balanceCents = 420000)),
    )

    private fun screen(status: CushionStatus): CushionViewModel = mockk<CushionViewModel>(relaxed = true) {
        every { uiState } returns MutableStateFlow(CushionUiState(status = status))
    }

    @Test
    fun `the head says Matelas, its state and the amount in whole euros`() {
        compose.setContent { CushionScreen(viewModel = screen(lent), onBack = {}) }

        compose.onNodeWithText("Trésorerie").assertIsDisplayed()
        compose.onNodeWithText("Matelas").assertIsDisplayed()
        compose.onNodeWithText("Constitution en cours").assertIsDisplayed()
        compose.onNodeWithText(formatCentsRounded(466500)).assertIsDisplayed()
    }

    @Test
    fun `the gap line tells what is missing and how much was lent`() {
        compose.setContent { CushionHead(lent) }

        compose.onNodeWithText(
            "Il manque ${formatCentsRounded(253500)}, dont ${formatCentsRounded(69000)} avancés à des proches.",
        ).assertIsDisplayed()
    }

    @Test
    fun `a complete cushion says the target is reached`() {
        compose.setContent { CushionHead(lent.copy(state = "complete", deficitCents = 0)) }

        compose.onNodeWithText("La cible est atteinte.").assertIsDisplayed()
    }

    @Test
    fun `under the bar, the critical threshold on the left and the target on the right`() {
        compose.setContent { CushionHead(lent) }

        compose.onNodeWithText("seuil critique ${formatCentsRounded(380000)}").assertIsDisplayed()
        compose.onNodeWithText("cible ${formatCentsRounded(720000)}").assertIsDisplayed()
    }

    @Test
    fun `the saving card is titled by the threshold and keeps the share rule`() {
        compose.setContent { CushionSavingCard(lent.copy(criticalDeficitCents = 1000)) }
        compose.onNodeWithText("Sous le seuil critique").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.CUSHION_SHARE_RULE).assertIsDisplayed()

        assertEquals("Au-dessus du seuil critique", cushionSavingTitle(lent))
        assertEquals("Rien à faire", cushionSavingTitle(lent.copy(deficitCents = 0)))
    }

    @Test
    fun `each cushion account is a line with its bank and its balance`() {
        compose.setContent { CushionAccounts(lent) }

        compose.onNodeWithText("COMPTES DU MATELAS").assertIsDisplayed()
        compose.onNodeWithText("Livret A").assertIsDisplayed()
        compose.onNodeWithText("Boursorama").assertIsDisplayed()
        compose.onNodeWithText(formatCentsRounded(420000)).assertIsDisplayed()
    }

    @Test
    fun `the row names what is lent and gives it back`() {
        compose.setContent { CushionAccounts(lent) }

        compose.onNodeWithTag(UiTags.CUSHION_PEER_CLAIMS).assertIsDisplayed()
        compose.onNodeWithText("Avancé à des proches").assertIsDisplayed()
        compose.onNodeWithText("Gîte de Crozon, Hellfest").assertIsDisplayed()
        compose.onNodeWithText("−${formatCentsRounded(69000)}").assertIsDisplayed()
    }

    @Test
    fun `without a créance there is no row`() {
        compose.setContent { CushionAccounts(lent.copy(peerClaimsCents = 0, peerClaimObjects = emptyList())) }

        compose.onNodeWithTag(UiTags.CUSHION_PEER_CLAIMS).assertDoesNotExist()
    }

    @Test
    fun `the settings are three lines and no field shows`() {
        compose.setContent { CushionScreen(viewModel = screen(lent), onBack = {}) }

        compose.onNodeWithText("RÉGLAGES").assertIsDisplayed()
        compose.onNodeWithText("Seuil critique").assertIsDisplayed()
        compose.onNodeWithText("2 mois de dépenses · ${formatCentsRounded(380000)}").assertIsDisplayed()
        compose.onNodeWithText("60 % au-delà du seuil").assertIsDisplayed()
        compose.onNodeWithText("3 mois de salaire · ${formatCentsRounded(720000)}").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.CUSHION_SHARE_PERCENT).assertDoesNotExist()
        compose.onNodeWithTag(UiTags.CUSHION_SAVE).assertDoesNotExist()
    }

    @Test
    fun `a tap on a setting opens the fields and saving sends the request`() {
        val viewModel = screen(lent)
        compose.setContent { CushionScreen(viewModel = viewModel, onBack = {}) }

        compose.onNodeWithText("Part du matelas").performClick()
        compose.onNodeWithTag(UiTags.CUSHION_SHARE_PERCENT).assertIsDisplayed()
        // Inside a ModalBottomSheet the touch is swallowed, see ScreenInteractions.tap.
        compose.onNodeWithTag(UiTags.CUSHION_SAVE).tap()

        verify { viewModel.configure(any()) }
        compose.onNodeWithTag(UiTags.CUSHION_SAVE).assertDoesNotExist()
    }

    @Test
    fun `the page carries no line of instructions, the figures and the settings stay`() {
        val viewModel = screen(lent.copy(isCappedByRechargeCap = true))
        compose.setContent { CushionScreen(viewModel = viewModel, onBack = {}) }

        compose.onNodeWithText("Le plafond mensuel allonge", substring = true).assertDoesNotExist()
        compose.onNodeWithText("La cible est un nombre de mois", substring = true).assertDoesNotExist()
        compose.onNodeWithText("En dessous, toute l'épargne", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Au-delà du seuil", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Matelas de sécurité").assertDoesNotExist()
        compose.onNodeWithText("Comptes comptés").assertDoesNotExist()
        compose.onNodeWithText("RÉGLAGES").assertIsDisplayed()
    }

    @Test
    fun `below the threshold the line gives the plan in whole euros without repeating the title`() {
        val status = lent.copy(criticalDeficitCents = 1000, monthlyRechargeCents = 100000, rechargeMonths = 15)

        assertEquals(
            "Toute l'épargne va au matelas : ${formatCentsRounded(100000)} par mois pendant 15 mois.",
            cushionSavingLine(status),
        )
    }

    @Test
    fun `cushion first and a 60 % share say their plan in whole euros`() {
        val first = lent.copy(cushionSharePercent = 100, monthlyRechargeCents = 100000, rechargeMonths = 15)
        val share = lent.copy(cushionSharePercent = 60, monthlyRechargeCents = 15000, rechargeMonths = 20)

        assertEquals(
            "Matelas d'abord : toute l'épargne va au matelas, ${formatCentsRounded(100000)} par mois pendant 15 mois.",
            cushionSavingLine(first),
        )
        assertEquals(
            "Seuil critique atteint : 60 % de l'épargne va au matelas, ${formatCentsRounded(15000)} par mois pendant 20 mois.",
            cushionSavingLine(share),
        )
    }

    @Test
    fun `without a plan the line ends at the rule`() {
        assertEquals(
            "Toute l'épargne va au matelas.",
            cushionSavingLine(lent.copy(criticalDeficitCents = 1000, monthlyRechargeCents = 0)),
        )
        assertEquals(
            "Seuil critique atteint : 60 % de l'épargne va au matelas.",
            cushionSavingLine(lent.copy(cushionSharePercent = 60, monthlyRechargeCents = 0)),
        )
    }

    @Test
    fun `a complete cushion has its title alone`() {
        val complete = lent.copy(state = "complete", deficitCents = 0)

        assertEquals(null, cushionSavingLine(complete))
        compose.setContent { CushionSavingCard(complete) }
        compose.onNodeWithText("Rien à faire").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.CUSHION_SHARE_RULE).assertDoesNotExist()
    }

    @Test
    fun `no amount on the screen carries cents`() {
        val status = lent.copy(
            criticalDeficitCents = 1000,
            monthlyRechargeCents = 100050,
            rechargeMonths = 15,
            criticalCents = 400099,
        )
        compose.setContent { CushionScreen(viewModel = screen(status), onBack = {}) }

        val cents = Regex("""\d,\d\d\s*€""")
        val withCents = SemanticsMatcher("text with cents") { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { cents.containsMatchIn(it.text) }
        }

        compose.onAllNodes(withCents).assertCountEquals(0)
    }
}

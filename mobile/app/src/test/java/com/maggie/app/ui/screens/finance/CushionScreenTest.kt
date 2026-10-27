package com.maggie.app.ui.screens.finance

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.CushionAccount
import com.maggie.app.data.model.CushionStatus
import com.maggie.app.data.model.formatCents
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** What the cushion says about the money lent to relatives (MAG-454). */
@RunWith(AndroidJUnit4::class)
class CushionScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private val lent = CushionStatus(
        state = "building",
        targetCents = 750000,
        currentCents = 466500,
        deficitCents = 283500,
        peerClaimsCents = 53500,
        peerClaimObjects = listOf("Gîte de Crozon", "Hellfest"),
        accounts = listOf(CushionAccount(id = "a1", name = "Livret A", balanceCents = 520000)),
    )

    @Test
    fun `the row names what is lent and gives the amount back at the repayment`() {
        compose.setContent { CushionSummaryCard(status = lent) }

        compose.onNodeWithTag(UiTags.CUSHION_PEER_CLAIMS).assertIsDisplayed()
        compose.onNodeWithText("Avancé à des proches (Gîte de Crozon, Hellfest)").assertIsDisplayed()
        compose.onNodeWithText("revient au remboursement").assertIsDisplayed()
        compose.onNodeWithText("−${formatCents(53500)}").assertIsDisplayed()
    }

    @Test
    fun `the deficit sentence tells how much of it was lent`() {
        compose.setContent { CushionSummaryCard(status = lent) }

        compose.onNodeWithText(
            "Il manque ${formatCents(283500)}, dont ${formatCents(53500)} avancés à des proches",
            substring = true,
        ).assertIsDisplayed()
    }

    @Test
    fun `without a créance there is no row`() {
        compose.setContent { CushionSummaryCard(status = lent.copy(peerClaimsCents = 0, peerClaimObjects = emptyList())) }

        compose.onNodeWithTag(UiTags.CUSHION_PEER_CLAIMS).assertDoesNotExist()
    }

    @Test
    fun `the page carries no line of instructions, the figures and the settings stay`() {
        val viewModel = mockk<CushionViewModel>(relaxed = true) {
            every { uiState } returns MutableStateFlow(CushionUiState(status = lent.copy(isCappedByRechargeCap = true)))
        }
        compose.setContent { CushionScreen(viewModel = viewModel, onBack = {}) }

        compose.onNodeWithText("Le plafond mensuel allonge", substring = true).assertDoesNotExist()
        compose.onNodeWithText("La cible est un nombre de mois", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Matelas de sécurité").assertIsDisplayed()
        compose.onNodeWithText("Réglages").assertIsDisplayed()
    }
}

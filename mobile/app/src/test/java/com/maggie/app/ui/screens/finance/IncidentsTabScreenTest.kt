package com.maggie.app.ui.screens.finance

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.AccountIncident
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.screentest.assertTopToBottom
import com.maggie.app.ui.UiTags
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The rejections tab as the « Rejets » mock-up draws it (MAG-947). */
@RunWith(AndroidJUnit4::class)
class IncidentsTabScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private val edf = AccountIncident(
        debitId = "d1", creditId = "c1", bookedAt = "2020-10-03", rejectedAt = "2020-10-06",
        counterpartyName = "EDF", amountCents = 7800, kind = "direct_debit",
    )
    private val freeMobile = AccountIncident(
        debitId = "d2", creditId = "c2", bookedAt = "2020-08-10", rejectedAt = "2020-08-13",
        counterpartyName = "Free Mobile", amountCents = 1999, kind = "direct_debit", settledAt = "2020-08-14",
    )

    private fun show(
        incidents: List<AccountIncident> = emptyList(),
        settled: List<AccountIncident> = emptyList(),
        settling: String? = null,
        onOpen: (AccountIncident) -> Unit = {},
        onSettle: (AccountIncident) -> Unit = {},
        onUnsettle: (AccountIncident) -> Unit = {},
    ) = compose.setContent {
        IncidentsTab(
            incidents = incidents,
            isLoading = false,
            error = null,
            onOpen = onOpen,
            onRetry = {},
            settled = settled,
            settling = settling,
            onSettle = onSettle,
            onUnsettle = onUnsettle,
            accountName = "Compte courant",
        )
    }

    @Test
    fun `a rejection to settle shows its payee, its kind and account, its amount and its two dates`() {
        show(incidents = listOf(edf))

        compose.onNodeWithText("À RÉGULARISER").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.INCIDENT_ROW).assertIsDisplayed()
        compose.onNodeWithText("EDF").assertIsDisplayed()
        compose.onNodeWithText("Prélèvement · Compte courant").assertIsDisplayed()
        compose.onNodeWithText("78,00 €").assertIsDisplayed()
        compose.onNodeWithText("Débité le 3 oct. 2020 · rejeté le 6 oct. 2020").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.INCIDENT_SETTLE).assertIsDisplayed()
        compose.onNodeWithText("Régularisé").assertIsDisplayed()
    }

    @Test
    fun `a rejected transfer reads Virement, and one without a rejection day reads only its debit`() {
        show(incidents = listOf(edf.copy(kind = "transfer", rejectedAt = null)))

        compose.onNodeWithText("Virement · Compte courant").assertIsDisplayed()
        compose.onNodeWithText("Débité le 3 oct. 2020").assertIsDisplayed()
    }

    @Test
    fun `a tap on the card opens the rejection and Régularisé settles it`() {
        val opened = mutableListOf<AccountIncident>()
        val settledNow = mutableListOf<AccountIncident>()
        show(incidents = listOf(edf), onOpen = { opened += it }, onSettle = { settledNow += it })

        compose.onNodeWithText("EDF").performClick()
        compose.onNodeWithTag(UiTags.INCIDENT_SETTLE).performClick()

        assertEquals(listOf(edf), opened)
        assertEquals(listOf(edf), settledNow)
    }

    @Test
    fun `a settle in progress locks the buttons`() {
        show(incidents = listOf(edf), settled = listOf(freeMobile), settling = "d1|c1")

        compose.onNodeWithTag(UiTags.INCIDENT_SETTLE).assertIsNotEnabled()
        compose.onNodeWithTag(UiTags.INCIDENT_UNSETTLE).assertIsNotEnabled()
    }

    @Test
    fun `an empty pile with settled rejections says there is nothing left to settle`() {
        show(settled = listOf(freeMobile))

        compose.onNodeWithText("À RÉGULARISER").assertIsDisplayed()
        compose.onNodeWithText("Plus rien à régulariser.").assertIsDisplayed()
        compose.onAllNodesWithText("Aucun rejet à traiter.").assertCountEquals(0)
    }

    @Test
    fun `the settled rejections are listed unfolded with their amount, their rejection day and Annuler`() {
        show(incidents = listOf(edf), settled = listOf(freeMobile))

        compose.onNodeWithTag(UiTags.SETTLED_INCIDENTS_HEADER).assertIsDisplayed()
        compose.onNodeWithText("RÉGULARISÉS").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.SETTLED_INCIDENT_ROW).assertIsDisplayed()
        compose.onNodeWithText("Free Mobile").assertIsDisplayed()
        compose.onNodeWithText("19,99 € · rejeté le 13 août 2020").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.INCIDENT_UNSETTLE).assertIsEnabled()
        compose.onAllNodesWithText("Remettre").assertCountEquals(0)
    }

    @Test
    fun `Annuler hands the settled rejection back`() {
        val back = mutableListOf<AccountIncident>()
        show(settled = listOf(freeMobile), onUnsettle = { back += it })

        compose.onNodeWithTag(UiTags.INCIDENT_UNSETTLE).performClick()

        assertEquals(listOf(freeMobile), back)
    }

    @Test
    fun `À régulariser comes above Régularisés`() {
        show(incidents = listOf(edf), settled = listOf(freeMobile))

        assertTopToBottom(
            "À RÉGULARISER" to compose.onNode(hasText("À RÉGULARISER")),
            "RÉGULARISÉS" to compose.onNode(hasText("RÉGULARISÉS")),
        )
    }

    @Test
    fun `a long payee name keeps 12 dp away from the amount`() {
        val longName = "ORGANISME DE RECOUVREMENT DES CREANCES PUBLIQUES FRANCE"
        show(incidents = listOf(edf.copy(counterpartyName = longName, amountCents = 20600)))

        val name = compose.onNodeWithText(longName, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val amount = compose.onNodeWithText("206,00 €", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val gap = 12 * ApplicationProvider.getApplicationContext<Application>().resources.displayMetrics.density

        assertTrue("name ends at ${name.right}, amount starts at ${amount.left}", name.right + gap <= amount.left + 0.5f)
    }

    @Test
    fun `without any rejection the tab says so, without a line of explanation`() {
        show()

        compose.onNodeWithText("Aucun incident sur ce compte").assertIsDisplayed()
        compose.onAllNodesWithText("Un prélèvement ou un virement rejeté", substring = true).assertCountEquals(0)
    }
}

package com.maggie.app.ui.screens.finance

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.model.Placement
import com.maggie.app.data.model.PlacementPoint
import com.maggie.app.data.model.PlacementSimulation
import com.maggie.app.data.repository.PlacementRepository
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.screentest.assertTopToBottom
import com.maggie.app.ui.UiTags
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * « Placements » as drawn (MAG-555): on a phone, the projection card of the selected placement over
 * the rows; a tap that selects, a tap on the selected one that opens the form; the empty state; the
 * form that will not send a placement paying nothing. On a tablet, the three columns and a stepper.
 */
@RunWith(AndroidJUnit4::class)
class PlacementListScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private val repository = mockk<PlacementRepository>()

    private val pea = Placement(
        id = "pea", name = "PEA", initial = 200000, deposit = 15000, frequency = "monthly", annualRate = 600, years = 20,
        finalCents = 7443007, paid = 3800000, interest = 3643007, rent = 24810, monthlyDeposit = 15000,
    )
    private val life = Placement(
        id = "life", name = "Assurance-vie", deposit = 120000, frequency = "yearly", annualRate = 350, years = 15,
        finalCents = 2400000, paid = 1800000, interest = 600000, rent = 8000, monthlyDeposit = 10000,
    )
    private val book = Placement(
        id = "book", name = "Livret jeune de Léo", initial = 50000, frequency = "once", annualRate = 300, years = 10,
        finalCents = 67196, paid = 50000, interest = 17196, rent = 224,
    )

    private fun curve(of: Placement) = PlacementSimulation(
        finalCents = of.finalCents, paid = of.paid, interest = of.interest, rent = of.rent,
        curve = listOf(PlacementPoint(0, of.initial.toLong(), of.initial.toLong()), PlacementPoint(of.years * 12, of.finalCents, of.paid)),
    )

    private fun viewModel(placements: List<Placement> = listOf(pea, life, book)): PlacementViewModel {
        coEvery { repository.getPlacements() } returns Result.success(placements)
        placements.forEach { coEvery { repository.getSimulation(it.id) } returns Result.success(curve(it)) }
        val auth = mockk<AuthRepository>()
        coEvery { auth.getUserId() } returns "user-1"
        val mercure = mockk<MercureService>()
        every { mercure.subscribe(any()) } returns emptyFlow()
        return PlacementViewModel(repository, mercure, auth)
    }

    private fun waitForRows(count: Int) =
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(UiTags.PLACEMENT_ROW).fetchSemanticsNodes().size == count }

    @Test
    fun `the card projects the first placement over the rows, each with its terms and its capital`() {
        compose.setContent { PlacementListScreen(viewModel(), onBack = {}, onOpenForm = {}) }

        waitForRows(3)
        compose.onNodeWithText("PEA · capital dans 20 ans").assertIsDisplayed()
        compose.onNodeWithText("≈ 248\u00A0€ / mois de rente à 4 %").assertIsDisplayed()
        compose.onNodeWithText("versements 38\u00A0000\u00A0€").assertIsDisplayed()
        compose.onNodeWithText("intérêts 36\u00A0430\u00A0€").assertIsDisplayed()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(UiTags.PLACEMENT_CHART).fetchSemanticsNodes().isNotEmpty() }
        assertTopToBottom(
            "PEA" to compose.onNodeWithText("150\u00A0€ par mois · 6\u00A0% · 20 ans"),
            "Assurance-vie" to compose.onNodeWithText("1\u00A0200\u00A0€ par an · 3,5\u00A0% · 15 ans"),
            "Livret" to compose.onNodeWithText("500\u00A0€ une fois · 3\u00A0% · 10 ans"),
        )
    }

    @Test
    fun `a tap selects a placement, a tap on the selected one opens its form, « + » a new one`() {
        val opened = mutableListOf<String?>()
        compose.setContent { PlacementListScreen(viewModel(), onBack = {}, onOpenForm = { opened += it }) }
        waitForRows(3)

        compose.onNodeWithText("Assurance-vie").performClick()
        compose.onNodeWithText("Assurance-vie · capital dans 15 ans").assertIsDisplayed()
        compose.onNodeWithText("Assurance-vie").performClick()
        compose.onNodeWithContentDescription("Modifier Assurance-vie").performClick()
        compose.onNodeWithTag(UiTags.PLACEMENT_ADD).performClick()

        assertEquals(listOf("life", "life", null), opened)
        coVerify { repository.getSimulation("life") }
    }

    @Test
    fun `without a placement the screen offers to add one`() {
        val opened = mutableListOf<String?>()
        compose.setContent { PlacementListScreen(viewModel(emptyList()), onBack = {}, onOpenForm = { opened += it }) }

        compose.waitUntil(5_000) { compose.onAllNodesWithTag(UiTags.PLACEMENT_EMPTY).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Ajouter un placement").performClick()

        assertEquals(listOf<String?>(null), opened)
    }

    @Test
    fun `the form will not send a placement that pays nothing`() {
        val viewModel = viewModel()
        compose.setContent { PlacementFormScreen(viewModel, placementId = null, onClose = {}) }

        compose.waitUntil(5_000) { compose.onAllNodesWithTag(UiTags.PLACEMENT_NAME).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(UiTags.PLACEMENT_SAVE).performClick()

        compose.onNodeWithText(PlacementViewModel.MESSAGE_NOTHING_PAID).assertIsDisplayed()
        coVerify(exactly = 0) { repository.createPlacement(any()) }
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun `a tablet shows the list, the settings and the projection side by side, and a stepper moves the figure at once`() {
        coEvery { repository.updatePlacement("pea", any()) } returns Result.success(pea.copy(deposit = 16000))
        compose.setContent { PlacementListScreen(viewModel(), onBack = {}, onOpenForm = {}) }

        waitForRows(3)
        compose.onNodeWithText("250\u00A0€ par mois de versements en moyenne.").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.PLACEMENT_SETTINGS).assertIsDisplayed()
        compose.onNodeWithText("Capital dans 20 ans").assertIsDisplayed()
        compose.onNodeWithText("Dont intérêts composés").assertIsDisplayed()
        compose.onNodeWithText("Retiré à 4 % par an, ce capital donnerait environ 248\u00A0€ par mois de rente.").assertIsDisplayed()
        compose.onNodeWithText("composés, réinvestis").assertIsDisplayed()

        compose.onNodeWithTag(UiTags.placementStep("deposit", plus = true)).performClick()
        compose.onNodeWithText("160\u00A0€").assertIsDisplayed()

        compose.onNodeWithText("Livret jeune de Léo").performClick()
        compose.onNodeWithTag(UiTags.placementFrequency("once")).assertIsDisplayed()
        // Paid once, no deposit: the deposit cannot go lower.
        compose.onNodeWithTag(UiTags.placementStep("deposit", plus = false)).assertIsNotEnabled()
    }
}

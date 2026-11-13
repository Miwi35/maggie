package com.maggie.app.ui.screens.fullcalendar

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.model.Agenda
import com.maggie.app.data.model.Period
import com.maggie.app.data.repository.AgendaRepository
import com.maggie.app.data.repository.PeriodRepository
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
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * « Périodes » as drawn (MAG-815): the pills, « En cours » then the months, the dates of a row, the empty and error
 * states, the form's refusal of a nameless period; on a tablet the form beside the list with its placeholder.
 */
@RunWith(AndroidJUnit4::class)
class PeriodListScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private val repository = mockk<PeriodRepository>()
    private val clock = Clock.fixed(Instant.parse("2026-10-25T10:00:00Z"), ZoneId.of("Europe/Paris"))

    private val custody = Agenda(id = "a1", name = "Garde", color = "#FF5722", isDefault = true)
    private val garde = Period(
        id = "p1", name = "Garde Léo", agenda = "/api/agendas/a1",
        startAt = "2026-10-23T16:00:00Z", endAt = "2026-10-30T17:00:00Z", rrule = "FREQ=WEEKLY;INTERVAL=2",
    )
    private val voyage = Period(
        id = "p2", name = "Voyage", agenda = "/api/agendas/a1", allDay = true,
        startDate = "2026-12-18", endDate = "2027-01-02",
    )
    private val ancienne = Period(
        id = "p3", name = "Rentrée", agenda = "/api/agendas/a1", allDay = true,
        startDate = "2026-09-01", endDate = "2026-09-05",
    )

    private fun viewModel(periods: List<Period> = listOf(garde, voyage, ancienne), loaded: Boolean = true): PeriodViewModel {
        coEvery { repository.getPeriods() } returns
            if (loaded) Result.success(periods) else Result.failure(RuntimeException("offline"))
        val agendas = mockk<AgendaRepository>()
        coEvery { agendas.loadAgendas() } returns Result.success(listOf(custody))
        val auth = mockk<AuthRepository>()
        coEvery { auth.getUserId() } returns "user-1"
        val mercure = mockk<MercureService>()
        every { mercure.subscribe(any()) } returns emptyFlow()
        return PeriodViewModel(repository, agendas, mercure, auth, clock)
    }

    private fun waitForTag(tag: String) =
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun `À venir lists the period in progress then the month, with the dates and the agenda`() {
        compose.setContent { PeriodListScreen(viewModel(), showsDetailPane = false, onOpenForm = {}) }

        waitForTag(UiTags.periodRow("p1"))
        compose.onNodeWithText("À venir").assertIsDisplayed()
        compose.onNodeWithText("Passées").assertIsDisplayed()
        compose.onNodeWithText("ven. 23 oct. 18:00 → ven. 30 oct. 18:00 · une semaine sur deux").assertIsDisplayed()
        compose.onNodeWithText("18 déc. → 1er janv.").assertIsDisplayed()
        assertTopToBottom(
            "En cours" to compose.onNodeWithText("En cours"),
            "Garde Léo" to compose.onNodeWithText("Garde Léo"),
            "Décembre 2026" to compose.onNodeWithText("Décembre 2026"),
            "Voyage" to compose.onNodeWithText("Voyage"),
        )
    }

    @Test
    fun `Passées lists the ended ones`() {
        compose.setContent { PeriodListScreen(viewModel(), showsDetailPane = false, onOpenForm = {}) }
        waitForTag(UiTags.periodRow("p1"))

        compose.onNodeWithTag(UiTags.PERIOD_TAB_PAST).performClick()

        compose.onNodeWithText("Rentrée").assertIsDisplayed()
        compose.onNodeWithText("Septembre 2026").assertIsDisplayed()
    }

    @Test
    fun `a row opens its form, « Nouvelle période » a new one`() {
        val opened = mutableListOf<String?>()
        compose.setContent { PeriodListScreen(viewModel(), showsDetailPane = false, onOpenForm = { opened += it }) }
        waitForTag(UiTags.periodRow("p1"))

        compose.onNodeWithTag(UiTags.periodRow("p2")).performClick()
        compose.onNodeWithTag(UiTags.PERIOD_ADD).performClick()

        assertEquals(listOf("p2", null), opened)
    }

    @Test
    fun `without a period the screen says so and offers to add one`() {
        val opened = mutableListOf<String?>()
        compose.setContent { PeriodListScreen(viewModel(emptyList()), showsDetailPane = false, onOpenForm = { opened += it }) }

        waitForTag(UiTags.PERIOD_EMPTY)
        compose.onNodeWithText("Aucune période à venir").assertIsDisplayed()
        compose.onNodeWithText("Nouvelle période").performClick()

        assertEquals(listOf<String?>(null), opened)
    }

    @Test
    fun `a list that cannot be read says so and offers to retry`() {
        compose.setContent { PeriodListScreen(viewModel(loaded = false), showsDetailPane = false, onOpenForm = {}) }

        waitForTag(UiTags.PERIOD_EMPTY)
        compose.onNodeWithText("Périodes indisponibles").assertIsDisplayed()
        compose.onNodeWithText("Réessayer").assertIsDisplayed()
    }

    @Test
    fun `the form will not send a period without a name`() {
        val viewModel = viewModel()
        compose.setContent { PeriodFormScreen(viewModel, periodId = null, onClose = {}) }

        waitForTag(UiTags.PERIOD_NAME)
        compose.onNodeWithText("Période").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.PERIOD_SAVE).performClick()

        compose.onNodeWithText("Donnez un nom à la période.").assertIsDisplayed()
        coVerify(exactly = 0) { repository.createPeriod(any()) }
    }

    @Test
    fun `an existing period offers its deletion behind a confirmation`() {
        coEvery { repository.deletePeriod("p2") } returns Result.success(Unit)
        val viewModel = viewModel()
        compose.setContent { PeriodFormScreen(viewModel, periodId = "p2", onClose = {}) }

        waitForTag(UiTags.PERIOD_DELETE)
        compose.onNodeWithTag(UiTags.PERIOD_DELETE).performClick()
        compose.onNodeWithText("Supprimer « Voyage » ?").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.PERIOD_DELETE_CONFIRM).performClick()

        compose.waitUntil(5_000) { viewModel.uiState.value.periods.none { it.id == "p2" } }
        coVerify { repository.deletePeriod("p2") }
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun `a tablet shows the list and a placeholder, a tap fills the pane with the form and highlights the row`() {
        val viewModel = viewModel()
        compose.setContent { PeriodListScreen(viewModel, showsDetailPane = true, onOpenForm = {}) }
        waitForTag(UiTags.periodRow("p1"))

        compose.onNodeWithText("Aucune période choisie").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.periodRow("p2")).performClick()

        waitForTag(UiTags.PERIOD_FORM)
        compose.onNodeWithText("Enregistrer").assertIsDisplayed()
        assertEquals("p2", viewModel.uiState.value.selectedId)
        assertEquals(0, compose.onAllNodesWithTag(UiTags.PERIOD_CLOSE).fetchSemanticsNodes().size)
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun `on a tablet a new period is created from the pane`() {
        coEvery { repository.createPeriod(any()) } returns Result.success(garde)
        val viewModel = viewModel()
        compose.setContent { PeriodListScreen(viewModel, showsDetailPane = true, onOpenForm = {}) }
        waitForTag(UiTags.periodRow("p1"))

        compose.onNodeWithTag(UiTags.PERIOD_ADD).performClick()
        waitForTag(UiTags.PERIOD_NAME)
        compose.onNodeWithTag(UiTags.PERIOD_NAME).performTextInput("Vacances")
        compose.onNodeWithTag(UiTags.PERIOD_SAVE).performClick()

        compose.waitUntil(5_000) { viewModel.uiState.value.form == null }
        coVerify { repository.createPeriod(any()) }
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun `on a tablet a period asked for by the calendar opens in the pane once the list is read`() {
        val viewModel = viewModel()
        var handled = false
        compose.setContent {
            PeriodListScreen(viewModel, showsDetailPane = true, onOpenForm = {}, requestedId = "p2", onRequestHandled = { handled = true })
        }

        waitForTag(UiTags.PERIOD_FORM)
        assertEquals("p2", viewModel.uiState.value.selectedId)
        assertEquals(true, handled)
    }
}

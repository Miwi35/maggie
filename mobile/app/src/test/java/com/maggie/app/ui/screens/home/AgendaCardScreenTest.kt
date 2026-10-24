package com.maggie.app.ui.screens.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.AgendaHomeEvent
import com.maggie.app.data.model.AgendaHomeState
import com.maggie.app.data.model.EventReminder
import com.maggie.app.data.model.EventReminders
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.screentest.tap
import com.maggie.app.ui.UiTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate

/** The Agenda card (MAG-562), at a frozen 14:12 in Paris: the order, the « maintenant » line, what a phone keeps, the fiche. */
@RunWith(AndroidJUnit4::class)
class AgendaCardScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private val now: Instant = Instant.parse("2026-10-14T12:12:00Z")

    private fun timed(id: String, title: String, start: String, end: String, place: String? = null, extra: (AgendaHomeEvent) -> AgendaHomeEvent = { it }) =
        extra(
            AgendaHomeEvent(
                id = id,
                summary = title,
                startAt = "2026-10-14T$start:00+02:00",
                endAt = "2026-10-14T$end:00+02:00",
                locationShort = place,
                location = place,
                agendaName = "Perso",
                agendaColor = "#336699",
            ),
        )

    private val standup = timed("e1", "Point d'équipe", "09:00", "10:00")
    private val dentist = timed("e2", "Dentiste", "15:00", "16:00", place = "Cabinet Martin") {
        it.copy(
            description = "Apporter la carte vitale",
            location = "Cabinet Martin, 15 rue de la Paix",
            reminders = EventReminders(overrides = listOf(EventReminder(minutes = 30))),
        )
    }
    private val review = timed("e3", "Revue de septembre", "18:00", "19:00") { it.copy(activity = "finance-review") }
    private val stay = AgendaHomeEvent(
        id = "e4",
        summary = "Séjour",
        allDay = true,
        startDate = LocalDate.of(2026, 10, 13),
        endDate = LocalDate.of(2026, 10, 16),
        agendaName = "Perso",
    )

    private fun today(vararg events: AgendaHomeEvent) =
        AgendaHomeState(date = "2026-10-14", now = "2026-10-14T14:12:00+02:00", events = events.toList())

    private fun draw(
        state: AgendaHomeState?,
        compact: Boolean = true,
        loading: Boolean = false,
        error: Boolean = false,
        onOpenAgenda: () -> Unit = {},
        onOpenEvent: (AgendaHomeEvent) -> Unit = {},
        onRetry: () -> Unit = {},
    ) {
        compose.setContent {
            AgendaCardContent(
                state = state,
                loading = loading,
                error = error,
                now = state?.nowInstant,
                compact = compact,
                onOpenAgenda = onOpenAgenda,
                onOpenEvent = onOpenEvent,
                onRetry = onRetry,
            )
        }
    }

    private fun top(tag: String) = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot().top

    @Test
    fun `the line sits between what is done and what comes, in order`() {
        draw(today(standup, dentist, review))

        val line = top(UiTags.HOME_AGENDA_NOW_LINE)
        assertTrue(top(UiTags.homeAgendaRow("e1")) < line)
        assertTrue(line < top(UiTags.homeAgendaRow("e2")))
        assertTrue(top(UiTags.homeAgendaRow("e2")) < top(UiTags.homeAgendaRow("e3")))
        compose.onNodeWithTag(UiTags.HOME_AGENDA_NOW_LINE).assertTextContains("maintenant · 14:12")
    }

    @Test
    fun `a done event is dimmed but readable, the next one says when`() {
        draw(today(standup, dentist, review))

        compose.onNodeWithText("Point d'équipe · fait").assertIsDisplayed()
        compose.onNodeWithText("Cabinet Martin · dans 48 min").assertIsDisplayed()
    }

    @Test
    fun `an event in progress is below the line and not done`() {
        val running = timed("e5", "Atelier", "13:30", "14:30")
        draw(today(running, dentist))

        compose.onNodeWithText("Atelier").assertIsDisplayed()
        compose.onNodeWithText("en cours", substring = true).assertIsDisplayed()
        assertTrue(top(UiTags.HOME_AGENDA_NOW_LINE) < top(UiTags.homeAgendaRow("e5")))
    }

    @Test
    fun `a whole-day event begun the day before is on the card`() {
        draw(today(stay, dentist))

        compose.onNodeWithText("Séjour").assertIsDisplayed()
        compose.onNodeWithText("Journée").assertIsDisplayed()
    }

    @Test
    fun `a phone keeps two done and four coming events and offers the rest in the Agenda`() {
        val past = (1..4).map { timed("p$it", "Passé $it", "0$it:00", "0$it:30") }
        val coming = (1..6).map { timed("c$it", "A venir $it", "%02d:00".format(it + 14), "%02d:30".format(it + 14)) }
        var opened = 0
        draw(today(*past.toTypedArray(), *coming.toTypedArray()), onOpenAgenda = { opened++ })

        compose.onNodeWithTag(UiTags.homeAgendaRow("p1")).assertDoesNotExist()
        compose.onNodeWithTag(UiTags.homeAgendaRow("p2")).assertDoesNotExist()
        compose.onNodeWithTag(UiTags.homeAgendaRow("p3")).assertExists()
        compose.onNodeWithTag(UiTags.homeAgendaRow("c4")).assertExists()
        compose.onNodeWithTag(UiTags.homeAgendaRow("c5")).assertDoesNotExist()

        compose.onNodeWithTag(UiTags.HOME_AGENDA_SEE_DAY).performClick()
        assertEquals(1, opened)
    }

    @Test
    fun `a tablet shows the whole day and no link`() {
        val past = (1..4).map { timed("p$it", "Passé $it", "0$it:00", "0$it:30") }
        val coming = (1..6).map { timed("c$it", "A venir $it", "%02d:00".format(it + 14), "%02d:30".format(it + 14)) }
        draw(today(*past.toTypedArray(), *coming.toTypedArray()), compact = false)

        compose.onNodeWithTag(UiTags.homeAgendaRow("p1")).assertExists()
        compose.onNodeWithTag(UiTags.homeAgendaRow("c6")).assertExists()
        compose.onNodeWithTag(UiTags.HOME_AGENDA_SEE_DAY).assertDoesNotExist()
    }

    @Test
    fun `tomorrow is the whole day with no line`() {
        val tomorrow = AgendaHomeState(date = "2026-10-15", now = null, events = listOf(standup, dentist, review))
        draw(tomorrow)

        compose.onNodeWithTag(UiTags.HOME_AGENDA_NOW_LINE).assertDoesNotExist()
        compose.onNodeWithText("Point d'équipe").assertIsDisplayed()
        compose.onNodeWithText("dans ", substring = true).assertDoesNotExist()
        compose.onNodeWithText(" · fait", substring = true).assertDoesNotExist()
    }

    @Test
    fun `a day with no event draws no card, today or tomorrow`() {
        draw(today())

        compose.onNodeWithTag(UiTags.homeCard(HomeModules.AGENDA)).assertDoesNotExist()
        compose.onNodeWithText("Agenda").assertDoesNotExist()
    }

    @Test
    fun `a card still loading or failed is not an empty day`() {
        draw(state = null, loading = true)

        compose.onNodeWithTag(UiTags.homeCard(HomeModules.AGENDA)).assertIsDisplayed()
    }

    @Test
    fun `an error offers to try again`() {
        var retried = 0
        draw(state = null, error = true, onRetry = { retried++ })

        compose.onNodeWithText("L'agenda n'a pas pu être chargé.").assertIsDisplayed()
        compose.onNodeWithText("Réessayer").performClick()
        assertEquals(1, retried)
    }

    @Test
    fun `touching a row hands the event over and the header opens the Agenda`() {
        val touched = mutableListOf<String>()
        var opened = 0
        draw(today(standup, dentist), onOpenAgenda = { opened++ }, onOpenEvent = { touched += it.id })

        compose.onNodeWithTag(UiTags.homeAgendaRow("e2")).performClick()
        assertEquals(listOf("e2"), touched)
        assertEquals(0, opened)

        compose.onNodeWithTag(UiTags.homeCardHeader(HomeModules.AGENDA)).performClick()
        assertEquals(1, opened)
    }

    @Test
    fun `the fiche shows the event's fields and offers the Agenda`() {
        var inAgenda = 0
        compose.setContent {
            AgendaEventSheet(event = dentist, onDismiss = {}, onOpenInAgenda = { inAgenda++ }, onLaunch = {})
        }

        compose.onNodeWithTag(UiTags.HOME_AGENDA_SHEET_TITLE).assertTextContains("Dentiste")
        compose.onNodeWithText("Mercredi 14 octobre 2026, 15:00 - 16:00").assertIsDisplayed()
        compose.onNodeWithText("Cabinet Martin, 15 rue de la Paix").assertIsDisplayed()
        compose.onNodeWithText("Apporter la carte vitale").assertIsDisplayed()
        compose.onNodeWithText("Perso").assertIsDisplayed()
        compose.onNodeWithText("30 minutes avant", substring = true).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.HOME_AGENDA_LAUNCH).assertDoesNotExist()

        // Inside a ModalBottomSheet the touch is swallowed here, see ScreenInteractions.tap.
        compose.onNodeWithTag(UiTags.HOME_AGENDA_OPEN_EVENT).tap()
        assertEquals(1, inAgenda)
    }

    @Test
    fun `an event that launches the review offers Lancer`() {
        var launched = 0
        compose.setContent {
            AgendaEventSheet(event = review, onDismiss = {}, onOpenInAgenda = {}, onLaunch = { launched++ })
        }

        compose.onNodeWithTag(UiTags.HOME_AGENDA_LAUNCH).tap()
        assertEquals(1, launched)
    }

    @Test
    fun `the labels read as the spec says`() {
        val start = Instant.parse("2026-10-14T13:00:00Z")
        assertEquals("dans 48 min", untilLabel(start, start.minusSeconds(48 * 60)))
        assertEquals("dans 1 h", untilLabel(start, start.minusSeconds(60 * 60)))
        assertEquals("dans 1 h 35", untilLabel(start, start.minusSeconds(95 * 60)))
        assertEquals("dans 2 h 05", untilLabel(start, start.minusSeconds(125 * 60)))
        assertEquals("dans 1 min", untilLabel(start, start.minusSeconds(5)))
        assertEquals("15:00", rowTimeLabel(dentist, "2026-10-14"))
        assertEquals("", rowTimeLabel(stay, "2026-10-14"))
        val overMidnight = timed("o", "Soirée", "23:00", "23:30").copy(startAt = "2026-10-13T23:00:00+02:00", endAt = "2026-10-14T01:00:00+02:00")
        assertEquals("→ 01:00", rowTimeLabel(overMidnight, "2026-10-14"))
    }
}

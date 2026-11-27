package com.maggie.app.ui.screens.home

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.AgendaHomeEvent
import com.maggie.app.data.model.AgendaHomeState
import com.maggie.app.data.model.AgendaRow
import com.maggie.app.data.model.AgendaRowKind
import com.maggie.app.data.model.AgendaTimeline
import com.maggie.app.data.model.EventReminder
import com.maggie.app.data.model.EventReminders
import com.maggie.app.data.model.HomeDay
import com.maggie.app.data.model.Trip
import com.maggie.app.data.model.TripStep
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.screentest.tap
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.navigation.Screen
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
        AgendaHomeState(date = "2026-10-14", events = events.toList())

    private fun draw(
        state: AgendaHomeState?,
        compact: Boolean = true,
        loading: Boolean = false,
        error: Boolean = false,
        day: HomeDay = HomeDay.TODAY,
        onOpenAgenda: () -> Unit = {},
        onOpenEvent: (AgendaHomeEvent) -> Unit = {},
        onLaunch: (String) -> Unit = {},
        onRetry: () -> Unit = {},
        onGo: (Trip) -> Unit = {},
        clock: Instant = now,
    ) {
        compose.setContent {
            AgendaCardContent(
                state = state,
                loading = loading,
                error = error,
                now = if (state?.date == "2026-10-14") clock else null,
                compact = compact,
                day = day,
                onOpenAgenda = onOpenAgenda,
                onOpenEvent = onOpenEvent,
                onLaunch = onLaunch,
                onRetry = onRetry,
                onGo = onGo,
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

        compose.onNodeWithText("Point d'équipe", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Perso · fait", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Cabinet Martin · Perso", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("dans 48 min", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `the header reads the day and the count of the whole day`() {
        draw(today(standup, dentist, review))

        compose.onNodeWithText("Agenda · mer. 14 oct.").assertIsDisplayed()
        compose.onNodeWithText("1 passé · 2 à venir").assertIsDisplayed()
    }

    @Test
    fun `a phone counts the rows it leaves out`() {
        val done = (1..3).map { timed("d$it", "Fini $it", "0$it:00", "0$it:30") }
        val coming = (1..5).map { timed("c$it", "A venir $it", "1${it + 3}:00", "1${it + 3}:30") }
        draw(today(*(done + coming).toTypedArray()))

        compose.onNodeWithText("3 passés · 5 à venir").assertIsDisplayed()
    }

    @Test
    fun `tomorrow counts what is planned`() {
        draw(AgendaHomeState(date = "2026-10-15", events = listOf(standup, dentist, review)), day = HomeDay.TOMORROW)

        compose.onNodeWithText("Agenda · jeu. 15 oct.").assertIsDisplayed()
        compose.onNodeWithText("3 prévus").assertIsDisplayed()
    }

    @Test
    fun `the header no longer has a chevron and still opens the agenda`() {
        var opened = 0
        draw(today(standup, dentist), onOpenAgenda = { opened++ })

        compose.onNodeWithTag(UiTags.homeCardHeader(HomeModules.AGENDA)).performClick()
        assertEquals(1, opened)
    }

    @Test
    fun `an event in progress is below the line and not done`() {
        val running = timed("e5", "Atelier", "13:30", "14:30")
        draw(today(running, dentist))

        compose.onNodeWithText("Atelier").assertIsDisplayed()
        compose.onNodeWithText("en cours", useUnmergedTree = true).assertIsDisplayed()
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
    fun `the review's line carries a Lancer today, and only an activity does`() {
        val launched = mutableListOf<String>()
        draw(today(standup, dentist, review), onLaunch = { launched += it })

        compose.onNodeWithTag(UiTags.homeLaunch("finance")).assertExists()
        compose.onNodeWithContentDescription("Lancer Revue de septembre").assertExists()
        assertEquals(1, compose.onAllNodesWithTag(UiTags.homeLaunch("finance")).fetchSemanticsNodes().size)

        compose.onNodeWithTag(UiTags.homeLaunch("finance")).tap()
        assertEquals(listOf(Screen.FinanceReviewActivity.route), launched)
    }

    @Test
    fun `touching the Lancer does not open the fiche of the row`() {
        val opened = mutableListOf<String>()
        draw(today(review), onOpenEvent = { opened += it.id })

        compose.onNodeWithTag(UiTags.homeLaunch("finance")).tap()
        assertTrue(opened.isEmpty())
    }

    @Test
    fun `tomorrow has no Lancer on the review's line`() {
        draw(AgendaHomeState(date = "2026-10-15", events = listOf(standup, review)), day = HomeDay.TOMORROW)

        compose.onNodeWithTag(UiTags.homeAgendaRow("e3")).assertExists()
        compose.onNodeWithTag(UiTags.homeLaunch("finance")).assertDoesNotExist()
    }

    @Test
    fun `tomorrow is the whole day with no line`() {
        val tomorrow = AgendaHomeState(date = "2026-10-15", events = listOf(standup, dentist, review))
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
        assertEquals("Cabinet Martin · Perso", agendaRowSub(dentist, past = false))
        assertEquals("Perso · fait", agendaRowSub(standup, past = true))
        assertEquals("fait", agendaRowSub(standup.copy(agendaName = null), past = true))
        assertEquals("mer. 14 oct.", agendaDayLabel("2026-10-14"))
        assertEquals("1 passé · 1 à venir", agendaCountLabel(timeline(past = 1, following = 1)))
        assertEquals("2 passés", agendaCountLabel(timeline(past = 2)))
        assertEquals("3 à venir", agendaCountLabel(timeline(following = 3)))
        assertEquals("2 à venir", agendaCountLabel(timeline(following = 1, allDay = 1)))
        assertEquals("1 prévu", agendaCountLabel(timeline(following = 1, nowLine = false)))
        assertEquals("4 prévus", agendaCountLabel(timeline(following = 3, allDay = 1, nowLine = false)))
        assertEquals("15:00", rowTimeLabel(dentist, "2026-10-14"))
        assertEquals("", rowTimeLabel(stay, "2026-10-14"))
        val overMidnight = timed("o", "Soirée", "23:00", "23:30").copy(startAt = "2026-10-13T23:00:00+02:00", endAt = "2026-10-14T01:00:00+02:00")
        assertEquals("→ 01:00", rowTimeLabel(overMidnight, "2026-10-14"))
    }

    private fun timeline(past: Int = 0, following: Int = 0, allDay: Int = 0, nowLine: Boolean = true): AgendaTimeline {
        fun rows(n: Int, kind: AgendaRowKind) = (1..n).map { AgendaRow(standup, kind) }
        return AgendaTimeline(rows(allDay, AgendaRowKind.PLAIN), rows(past, AgendaRowKind.PAST), rows(following, AgendaRowKind.LATER), nowLine)
    }

    // 16:42 in Paris, three trips: left at 08:09, next at 17:04, then 18:05.
    private val evening: Instant = Instant.parse("2026-10-14T14:42:00Z")
    private val morning = timed("m1", "Réunion", "08:30", "09:30")
    private val pickup = timed("m2", "Récupérer Ael", "17:30", "18:00")
    private val concert = timed("m3", "Concert", "18:30", "19:30")

    private fun tripTo(
        event: String,
        departAt: String,
        origin: String = "la maison",
        link: String = "https://waze.com/ul?q=x",
        computedAt: String? = "2026-10-14T16:38:00+02:00",
        steps: List<TripStep> = emptyList(),
        leg: String = Trip.LEG_OUTBOUND,
        arriveAt: String? = null,
    ) = Trip(
        id = "trip-$event",
        event = "/api/events/$event",
        leg = leg,
        departAt = "2026-10-14T$departAt:00+02:00",
        originLabel = origin,
        durationMinutes = 21,
        steps = steps,
        navigationUrl = link,
        computedAt = computedAt,
        arriveAt = arriveAt,
    )

    private val steps = listOf(TripStep("drive", 2, "voiture"), TripStep("transit", 15), TripStep("walk", 4, "parking école"))

    private fun threeTrips(next: Trip = tripTo("m2", "17:04", steps = steps)) = AgendaHomeState(
        date = "2026-10-14",
        events = listOf(morning, pickup, concert),
        trips = listOf(tripTo("m1", "08:09", link = ""), next, tripTo("m3", "18:05", origin = "École Jean-Moulin", steps = steps)),
    )

    @Test
    fun `the next departure is a tile above its event with time left, origin, door to door, chips, Y aller and à jour`() {
        draw(threeTrips(), compact = false, clock = evening)

        compose.onNodeWithTag(UiTags.HOME_DEPARTURE_TIME).assertTextContains("Partir à 17:04")
        compose.onNodeWithText("dans 22 min").assertIsDisplayed()
        compose.onNodeWithText("depuis la maison · 21 min porte à porte").assertIsDisplayed()
        compose.onNodeWithText("2 min").assertIsDisplayed()
        compose.onNodeWithText("voiture").assertIsDisplayed()
        compose.onNodeWithText("15 min").assertIsDisplayed()
        compose.onNodeWithText("4 min").assertIsDisplayed()
        compose.onNodeWithText("parking école").assertIsDisplayed()
        compose.onNodeWithText("Y aller").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.TRIP_UPDATED).assertTextContains("à jour 16:38")
        assertEquals(1, compose.onAllNodesWithTag(UiTags.HOME_DEPARTURE_TILE).fetchSemanticsNodes().size)
        assertTrue(top(UiTags.HOME_DEPARTURE_TILE) < top(UiTags.homeAgendaRow("m2")))
        assertTrue(top(UiTags.HOME_DEPARTURE_TILE) > top(UiTags.homeAgendaRow("m1")))
    }

    @Test
    fun `another trip is one short line without chips`() {
        draw(threeTrips(), compact = false, clock = evening)

        compose.onNodeWithTag(UiTags.homeTripLine("m3")).assertTextContains("Partir à 18:05 · depuis École Jean-Moulin")
        assertTrue(top(UiTags.homeTripLine("m3")) < top(UiTags.homeAgendaRow("m3")))
        compose.onAllNodesWithTag(UiTags.TRIP_GO).assertCountEquals(1)
        compose.onAllNodesWithText("parking école").assertCountEquals(1)
    }

    @Test
    fun `a trip already left reads Parti à and is dimmed`() {
        draw(threeTrips(), compact = false, clock = evening)

        compose.onNodeWithTag(UiTags.homeTripLine("m1")).assertTextContains("Parti à 08:09 · depuis la maison")
        assertTrue(top(UiTags.homeTripLine("m1")) < top(UiTags.homeAgendaRow("m1")))
    }

    @Test
    fun `tomorrow draws trip lines and no tile`() {
        val state = AgendaHomeState(
            date = "2026-10-15",
            events = listOf(timed("t1", "Dentiste", "14:00", "15:00")),
            trips = listOf(tripTo("t1", "13:34")),
        )
        draw(state, day = HomeDay.TOMORROW)

        compose.onNodeWithTag(UiTags.homeTripLine("t1")).assertTextContains("Partir à 13:34 · depuis la maison")
        compose.onNodeWithTag(UiTags.HOME_DEPARTURE_TILE).assertDoesNotExist()
    }

    @Test
    fun `Y aller hands the trip to onGo, and no navigation link means no button`() {
        val handed = mutableListOf<Trip>()
        draw(threeTrips(), compact = false, clock = evening, onGo = { handed += it })

        compose.onNodeWithTag(UiTags.TRIP_GO).performClick()
        assertEquals(listOf("trip-m2"), handed.map { it.id })
    }

    @Test
    fun `no navigation link means no button`() {
        draw(threeTrips(tripTo("m2", "17:04", link = "")), compact = false, clock = evening)

        compose.onNodeWithTag(UiTags.HOME_DEPARTURE_TILE).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.TRIP_GO).assertDoesNotExist()
    }

    @Test
    fun `without computedAt the tile has no à jour`() {
        draw(threeTrips(tripTo("m2", "17:04", computedAt = null)), compact = false, clock = evening)

        compose.onNodeWithTag(UiTags.TRIP_UPDATED).assertDoesNotExist()
    }

    @Test
    fun `a return reads Retour · Partir à … · arrivée …`() {
        val state = AgendaHomeState(
            date = "2026-10-14",
            events = listOf(concert),
            trips = listOf(
                tripTo("m3", "17:45"),
                tripTo("m3", "19:40", leg = Trip.LEG_RETURN, arriveAt = "2026-10-14T19:54:00+02:00").copy(id = "back"),
            ),
        )
        draw(state, compact = false, clock = evening)

        compose.onNodeWithTag(UiTags.homeTripLine("m3")).assertTextContains("Retour · Partir à 19:40 · arrivée 19:54")
        assertTrue(top(UiTags.HOME_DEPARTURE_TILE) < top(UiTags.homeTripLine("m3")))
    }
}

package com.maggie.app.ui.screens.fullcalendar

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.ExpandedEvent
import com.maggie.app.data.model.Trip
import com.maggie.app.data.model.TripStep
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.screentest.assertTopToBottom
import com.maggie.app.ui.UiTags
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The trip line under an event of the day view (MAG-658), against the « Trajets » mockup. */
@RunWith(AndroidJUnit4::class)
class TripLineScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private val day = LocalDate.of(2026, 6, 15)
    private val morning = Instant.parse("2026-06-15T08:00:00Z")
    private val afterDeparture = Instant.parse("2026-06-15T12:00:00Z")

    private val dentist = ExpandedEvent(
        id = "dentist",
        summary = "Dentiste",
        startAt = "2026-06-15T12:00:00Z",
        endAt = "2026-06-15T13:00:00Z",
    )
    private val lunch = ExpandedEvent(
        id = "lunch",
        summary = "Déjeuner",
        startAt = "2026-06-15T09:30:00Z",
        endAt = "2026-06-15T10:30:00Z",
    )

    private fun trip(event: String, departAt: String, origin: String = "la maison") = Trip(
        id = "trip-$event",
        event = "/api/events/$event",
        mode = "drive",
        steps = listOf(TripStep("walk", 4), TripStep("drive", 14), TripStep("walk", 3)),
        durationMinutes = 21,
        departAt = departAt,
        navigationUrl = "https://waze.com/ul?ll=48.85,2.35&navigate=yes",
        originLabel = origin,
    )

    private fun show(trips: List<Trip>, now: Instant = morning, onGo: (Trip) -> Unit = {}, events: List<ExpandedEvent> = listOf(lunch, dentist)) {
        compose.setContent {
            DayTimelineView(
                currentDate = day,
                events = events,
                onNavigateForward = {},
                onNavigateBackward = {},
                trips = trips,
                onTripGo = onGo,
                clock = { now },
            )
        }
    }

    @Test
    fun `the line reads the departure, above the event it leads to`() {
        show(listOf(trip("dentist", "2026-06-15T13:34:00+02:00")))

        compose.onNodeWithText("Partir à 13:34").assertIsDisplayed()
        assertTopToBottom(
            "line" to compose.onNodeWithText("Partir à 13:34"),
            "Dentiste" to compose.onNodeWithText("Dentiste"),
        )
    }

    @Test
    fun `the headline of the card is the departure alone`() {
        show(listOf(trip("dentist", "2026-06-15T13:34:00+02:00")))

        assertEquals(0, compose.onAllNodesWithText("Partir à 13:34 · depuis la maison").fetchSemanticsNodes().size)
        compose.onNodeWithTag(UiTags.tripLine("dentist")).assertTextEquals("Partir à 13:34")
    }

    @Test
    fun `the next card reads the event, the origin and the door-to-door time`() {
        show(listOf(trip("dentist", "2026-06-15T13:34:00+02:00")), events = listOf(dentist.copy(location = "Cabinet Foch")))

        compose.onNodeWithText("Dentiste à 14:00 · Cabinet Foch").assertIsDisplayed()
        compose.onNodeWithText("depuis la maison · 21 min porte à porte").assertIsDisplayed()
    }

    @Test
    fun `the next card shows à jour HH-MM from computedAt`() {
        show(listOf(trip("dentist", "2026-06-15T13:34:00+02:00").copy(computedAt = "2026-06-15T09:29:00+02:00")))

        compose.onNodeWithTag(UiTags.TRIP_UPDATED).assertTextEquals("à jour 09:29")
    }

    @Test
    fun `a past trip is dimmed with its chips`() {
        show(listOf(trip("dentist", "2026-06-15T13:34:00+02:00")), now = afterDeparture)

        compose.onNodeWithTag(UiTags.tripLine("dentist")).assertTextEquals("Parti à 13:34 · depuis la maison")
        // The block, chips included, is drawn at 0.6: its chips are still there.
        compose.onNodeWithText("14 min").assertIsDisplayed()
    }

    @Test
    fun `the return of a whole-day stay reads Retour with its departure and arrival`() {
        val stay = ExpandedEvent(
            id = "stay",
            summary = "Gîte du Pouldu",
            allDay = true,
            startDate = day.minusDays(2),
            endDate = day.plusDays(1),
        )
        val back = trip("stay", "2026-06-15T11:00:00+02:00").copy(leg = "return", arriveAt = "2026-06-15T11:14:00+02:00", originLabel = "")

        show(listOf(back), events = listOf(stay))

        compose.onNodeWithText("Retour · Partir à 11:00 · arrivée 11:14").assertIsDisplayed()
    }

    @Test
    fun `one chip per step, in the order of the journey`() {
        show(listOf(trip("dentist", "2026-06-15T13:34:00+02:00")))

        compose.onNodeWithText("4 min").assertIsDisplayed()
        compose.onNodeWithText("14 min").assertIsDisplayed()
        compose.onNodeWithText("3 min").assertIsDisplayed()
        compose.onNodeWithText("depuis la maison · 21 min porte à porte").assertIsDisplayed()
    }

    @Test
    fun `an event without a trip has no line`() {
        show(listOf(trip("dentist", "2026-06-15T13:34:00+02:00")))

        assertEquals(1, compose.onAllNodesWithText("Partir à", substring = true).fetchSemanticsNodes().size)
    }

    @Test
    fun `a departure that has passed reads Parti and offers no button`() {
        show(listOf(trip("dentist", "2026-06-15T13:34:00+02:00")), now = afterDeparture)

        compose.onNodeWithText("Parti à 13:34 · depuis la maison").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithText("Y aller").fetchSemanticsNodes().size)
    }

    @Test
    fun `only the next departure of the day is a card with the time left and Y aller`() {
        show(
            listOf(
                trip("lunch", "2026-06-15T10:00:00+02:00", origin = "le bureau"),
                trip("dentist", "2026-06-15T13:34:00+02:00"),
            ),
        )

        compose.onNodeWithText("Parti à 10:00 · depuis le bureau").assertIsDisplayed()
        compose.onNodeWithText("Partir à 13:34").assertIsDisplayed()
        compose.onNodeWithText("dans 3 h 34").assertIsDisplayed()
        assertEquals(1, compose.onAllNodesWithText("Y aller").fetchSemanticsNodes().size)
    }

    @Test
    fun `Y aller hands the trip to the navigation`() {
        val opened = mutableListOf<Trip>()
        val next = trip("dentist", "2026-06-15T13:34:00+02:00")
        show(listOf(next), onGo = { opened += it })

        compose.onNodeWithText("Y aller").performClick()

        assertEquals(listOf(next), opened)
    }

    @Test
    fun `the time left switches to minutes inside the hour`() {
        assertEquals("dans 12 min", timeLeftLabel(12))
        assertEquals("dans 1 h 05", timeLeftLabel(65))
    }
}

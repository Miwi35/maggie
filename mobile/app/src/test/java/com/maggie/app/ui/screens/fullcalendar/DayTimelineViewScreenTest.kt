package com.maggie.app.ui.screens.fullcalendar

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.ExpandedEvent
import com.maggie.app.data.model.Trip
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import com.maggie.app.screentest.FakeCalendar
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The day view as a list of the day (MAG-859), against `Periodes-Jour-Tel`. */
@RunWith(AndroidJUnit4::class)
class DayTimelineViewScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private val day = LocalDate.of(2026, 6, 15)
    private val noon = Instant.parse("2026-06-15T10:00:00Z")

    @Test
    fun `the trip of a 5-minute event sits above the event and overlaps no row`() {
        val short = ExpandedEvent(
            id = "short",
            summary = "Passer un colis",
            startAt = "2026-06-15T16:25:00Z",
            endAt = "2026-06-15T16:30:00Z",
        )
        val trip = Trip(
            id = "trip-short",
            event = "/api/events/short",
            durationMinutes = 21,
            departAt = "2026-06-15T17:39:00+02:00",
            originLabel = "la maison",
        )

        compose.setContent {
            DayTimelineView(
                currentDate = day,
                events = listOf(short),
                onNavigateForward = {},
                onNavigateBackward = {},
                trips = listOf(trip),
                clock = { noon },
            )
        }

        val line = compose.onNodeWithTag(UiTags.tripLine("short")).getBoundsInRoot()
        val title = compose.onNodeWithText("Passer un colis").getBoundsInRoot()
        assertTrue("trip $line must end above the event $title", line.bottom <= title.top)
    }

    private val gym = ExpandedEvent(
        id = "gym",
        summary = "Séance haut du corps",
        location = "Salle de sport",
        agendaName = "Santé",
        agendaColor = "#4CAF50",
        startAt = "2026-06-15T16:30:00Z",
        endAt = "2026-06-15T17:30:00Z",
    )
    private val done = ExpandedEvent(
        id = "done",
        summary = "Café",
        startAt = "2026-06-15T07:00:00Z",
        endAt = "2026-06-15T07:30:00Z",
    )

    private fun show(events: List<ExpandedEvent>) = compose.setContent {
        DayTimelineView(
            currentDate = day,
            events = events,
            onNavigateForward = {},
            onNavigateBackward = {},
            clock = { noon },
        )
    }

    @Test
    fun `the day view has no hour labels nor toolbar and reads the date line`() {
        val fake = FakeCalendar()
        compose.setContent { FullCalendarScreen(viewModel = fake.viewModel) }
        fake.viewModel.navigateToDate(day)
        fake.viewModel.setViewType(CalendarViewType.DAY)

        compose.onNodeWithTag(UiTags.calendarDayDate(day)).assertTextEquals("lundi 15 juin")
        compose.onNodeWithTag(UiTags.CALENDAR_VIEW_MONTH).assertDoesNotExist()
        compose.onNodeWithTag(UiTags.CALENDAR_NEXT).assertDoesNotExist()
        compose.onNodeWithText("08:00").assertDoesNotExist()
    }

    @Test
    fun `an event row reads time, title and place · agenda`() {
        show(listOf(gym))

        compose.onNodeWithText("18:30").assertIsDisplayed()
        compose.onNodeWithText("Séance haut du corps").assertIsDisplayed()
        compose.onNodeWithText("Salle de sport · Santé").assertIsDisplayed()
    }

    @Test
    fun `an ended event is dimmed and the next one is medium`() {
        show(listOf(done, gym))

        compose.onNodeWithText("Café").assertIsDisplayed()
        compose.onNodeWithText("Séance haut du corps").assertIsDisplayed()
    }
}

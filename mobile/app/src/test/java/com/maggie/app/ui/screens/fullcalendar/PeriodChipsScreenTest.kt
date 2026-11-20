package com.maggie.app.ui.screens.fullcalendar

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.Agenda
import com.maggie.app.data.model.Period
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate

/** The chips under the date of the Day and Week views (MAG-815): what they say, where they sit, what a tap does. */
@RunWith(AndroidJUnit4::class)
class PeriodChipsScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private val agendas = listOf(Agenda(id = "a1", name = "Garde", color = "#FF5722", isDefault = true))
    private val garde = Period(
        id = "p1", name = "Garde Léo", agenda = "/api/agendas/a1",
        startAt = "2026-10-23T16:00:00Z", endAt = "2026-10-30T17:00:00Z", rrule = "FREQ=WEEKLY;INTERVAL=2",
    )
    private val voyage = Period(
        id = "p2", name = "Voyage", agenda = "/api/agendas/a1", allDay = true,
        startDate = "2026-10-26", endDate = "2026-11-02",
    )

    private val friday = LocalDate.of(2026, 10, 30)

    @Test
    fun `a day lists the periods that cover it, each saying until when`() {
        compose.setContent {
            DayTimelineView(
                currentDate = friday, events = emptyList(), onNavigateForward = {}, onNavigateBackward = {},
                periods = listOf(garde, voyage), agendas = agendas,
            )
        }

        compose.onNodeWithTag(UiTags.periodChip("p1")).assertIsDisplayed()
        compose.onNodeWithText("Garde Léo").assertIsDisplayed()
        compose.onNodeWithText("jusqu'au ven. 18:00").assertIsDisplayed()
        compose.onNodeWithText("jusqu'au 1er nov.").assertIsDisplayed()
    }

    private val outgoing = Period(
        id = "pa", name = "Garde A", agenda = "/api/agendas/a1",
        startAt = "2026-11-13T15:00:00Z", endAt = "2026-11-20T15:00:00Z",
    )
    private val incoming = Period(
        id = "pb", name = "Garde B", agenda = "/api/agendas/a1",
        startAt = "2026-11-20T15:00:00Z", endAt = "2026-11-27T15:00:00Z",
    )

    private fun dayAt(day: LocalDate, now: String) = compose.setContent {
        DayTimelineView(
            currentDate = day, events = emptyList(), onNavigateForward = {}, onNavigateBackward = {},
            periods = listOf(outgoing, incoming), agendas = agendas, clock = { Instant.parse(now) },
        )
    }

    @Test
    fun `today, a period that ended this afternoon is no longer a chip`() {
        dayAt(LocalDate.of(2026, 11, 20), "2026-11-20T20:33:00Z")

        compose.onNodeWithTag(UiTags.periodChip("pb")).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.periodChip("pa")).assertDoesNotExist()
    }

    @Test
    fun `today before the end, both periods are chips`() {
        dayAt(LocalDate.of(2026, 11, 20), "2026-11-20T09:00:00Z")

        compose.onNodeWithTag(UiTags.periodChip("pa")).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.periodChip("pb")).assertIsDisplayed()
    }

    @Test
    fun `another day lists every period that covers it`() {
        dayAt(LocalDate.of(2026, 11, 19), "2026-11-20T20:33:00Z")

        compose.onNodeWithTag(UiTags.periodChip("pa")).assertIsDisplayed()
    }

    @Test
    fun `a day that no period covers draws no chip`() {
        compose.setContent {
            DayTimelineView(
                currentDate = LocalDate.of(2026, 12, 1), events = emptyList(), onNavigateForward = {}, onNavigateBackward = {},
                periods = listOf(garde, voyage), agendas = agendas,
            )
        }

        compose.onNodeWithTag(UiTags.periodChip("p1")).assertDoesNotExist()
        compose.onNodeWithTag(UiTags.periodChip("p2")).assertDoesNotExist()
    }

    @Test
    fun `a week lists a period once however many of its days it covers, and a tap opens it`() {
        var opened: Period? = null
        compose.setContent {
            WeekTimelineView(
                currentDate = friday, events = emptyList(), onNavigateForward = {}, onNavigateBackward = {},
                periods = listOf(garde, voyage), agendas = agendas, onPeriodClick = { opened = it },
            )
        }

        compose.onNodeWithTag(UiTags.periodChip("p2")).assertIsDisplayed().performClick()

        assertEquals("p2", opened?.id)
    }

    @Test
    fun `the occurrences of a series are one chip`() {
        val again = garde.copy(startAt = "2026-10-26T16:00:00Z", endAt = "2026-10-27T17:00:00Z")
        val week = (26L..30L).map { LocalDate.of(2026, 10, it.toInt()) }

        assertEquals(1, periodsOnDays(listOf(garde, again), week).size)
    }

    @Test
    fun `two short periods sit side by side`() {
        compose.setContent {
            DayTimelineView(
                currentDate = friday, events = emptyList(), onNavigateForward = {}, onNavigateBackward = {},
                periods = listOf(garde, voyage), agendas = agendas,
            )
        }

        val first = compose.onNodeWithTag(UiTags.periodChip("p1")).getBoundsInRoot()
        val second = compose.onNodeWithTag(UiTags.periodChip("p2")).getBoundsInRoot()
        assertEquals(first.top, second.top)
        assertTrue(first.right <= second.left)
    }
}

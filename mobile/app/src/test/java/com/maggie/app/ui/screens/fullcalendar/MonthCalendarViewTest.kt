package com.maggie.app.ui.screens.fullcalendar

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.ExpandedEvent
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.screentest.Seed
import com.maggie.app.ui.UiTags
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The month grid (MAG-335): the same slot sits at the same height in every column
 * of a week, whichever day is today, and « +N » is whole and only there when
 * events really do not fit.
 *
 * Today is handed to the view rather than read from the clock, and the week is the
 * one around the 15th of the current month, so the three days used here are always
 * in the month the grid opens on, whatever day the suite runs.
 */
@RunWith(AndroidJUnit4::class)
class MonthCalendarViewTest {

    @get:Rule
    val compose = ScreenRule()

    private val monday: LocalDate = YearMonth.now().atDay(15).with(DayOfWeek.MONDAY)
    private val tuesday = monday.plusDays(1)
    private val wednesday = monday.plusDays(2)
    private val thursday = monday.plusDays(3)

    @Test
    fun `a bar crossing today stays at the same height in the column of today and its neighbours`() {
        val events = listOf(
            allDay("wedding", "Mariage", tuesday, thursday),
            allDay("bins", "Poubelle", wednesday, thursday),
        )
        compose.setContent { tallGrid(events) }

        for (slot in 0..1) {
            val tops = listOf(tuesday, wednesday, thursday)
                .filter { slot == 0 || it != tuesday }
                .map { top(UiTags.calendarMonthSlot(it, slot)) }
            assertEquals("slot $slot, tops of $tops", 1, tops.distinct().size)
        }
    }

    @Test
    fun `the first slot starts at the same height on every day, today or not`() {
        val events = listOf(allDay("wedding", "Mariage", tuesday, thursday))
        compose.setContent { tallGrid(events) }

        assertEquals(top(UiTags.calendarMonthSlot(tuesday, 0)), top(UiTags.calendarMonthSlot(wednesday, 0)))
    }

    @Test
    fun `a full cell shows its counter whole, inside the cell`() {
        val events = (1..6).map { timed("meal-$it", "Repas $it", wednesday, LocalTime.of(7 + it, 0)) }
        compose.setContent {
            Box(Modifier.height(380.dp)) { MonthCalendarView(wednesday, events, {}, today = wednesday) }
        }

        val cell = bounds(UiTags.calendarMonthDay(wednesday))
        val more = bounds(UiTags.calendarMonthMore(wednesday))
        assertTrue("« +N » $more must be inside its cell $cell", more.bottom <= cell.bottom && more.top >= cell.top)

        val shown = compose
            .onAllNodes(hasTagPrefix("${UiTags.CALENDAR_MONTH_SLOT_PREFIX}${wednesday}_"), useUnmergedTree = true)
            .fetchSemanticsNodes().size
        assertTrue("at least one event is still drawn", shown >= 1)
        compose.onNodeWithTag(UiTags.calendarMonthMore(wednesday), useUnmergedTree = true)
            .assertTextEquals("+${events.size - shown}")
    }

    @Test
    fun `no counter when every event of a day fits`() {
        val events = (1..4).map { timed("meal-$it", "Repas $it", wednesday, LocalTime.of(7 + it, 0)) }
        compose.setContent { tallGrid(events) }

        compose.onNodeWithTag(UiTags.calendarMonthSlot(wednesday, 3), useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(UiTags.calendarMonthMore(wednesday), useUnmergedTree = true).assertDoesNotExist()
    }

    @Composable
    private fun tallGrid(events: List<ExpandedEvent>) {
        Box(Modifier.height(900.dp)) { MonthCalendarView(wednesday, events, {}, today = wednesday) }
    }

    private fun top(tag: String) = bounds(tag).top

    private fun bounds(tag: String) =
        compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun hasTagPrefix(prefix: String) = SemanticsMatcher("tag starts with $prefix") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
    }

    private fun allDay(id: String, summary: String, first: LocalDate, last: LocalDate) = ExpandedEvent(
        id = id,
        summary = summary,
        allDay = true,
        startDate = first,
        endDate = last.plusDays(1),
    )

    private fun timed(id: String, summary: String, day: LocalDate, at: LocalTime) = ExpandedEvent(
        id = id,
        summary = summary,
        startAt = Seed.instant(day, at),
        endAt = Seed.instant(day, at.plusHours(1)),
    )
}

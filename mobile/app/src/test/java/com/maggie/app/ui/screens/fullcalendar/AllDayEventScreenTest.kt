package com.maggie.app.ui.screens.fullcalendar

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.Event
import com.maggie.app.screentest.FakeCalendar
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * MAG-382, through the screen and the way `11-calendar-all-day-series` walks it: a
 * one-day event on the 1st is in the month on the 1st, in the day view of the 1st,
 * and not in the day view of the 2nd — where Paris's offset used to push it.
 */
@RunWith(AndroidJUnit4::class)
class AllDayEventScreenTest {

    @get:Rule
    val compose = ScreenRule()

    // The 1st of the month the calendar opens on, as the e2e fixture has it.
    private val first: LocalDate = YearMonth.now().atDay(1)
    private val summary = "Journée du 1er MAG-382"
    private val journee = Event(
        id = "01JOURNEE",
        summary = summary,
        allDay = true,
        startDate = first.toString(),
        endDate = first.toString(),
    )

    @Test
    fun `a one-day event on the 1st is in the month and the day of the 1st, not the 2nd`() {
        val fake = FakeCalendar(journee)
        compose.setContent { FullCalendarScreen(viewModel = fake.viewModel) }
        fake.viewModel.navigateToDate(first)

        compose.onNodeWithTag(UiTags.CALENDAR_VIEW_MONTH).performClick()
        // Merged tree, as Maestro reads it: the chip's tag and its text on one node.
        compose.onNodeWithTag(UiTags.calendarMonthSlot(first, 0)).assertTextEquals(summary)
        compose.onNodeWithTag(UiTags.calendarMonthSlot(first.plusDays(1), 0), useUnmergedTree = true).assertDoesNotExist()

        compose.onNodeWithTag(UiTags.calendarMonthDate(first)).performClick()
        compose.onNodeWithTag(UiTags.calendarDayView(first)).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.calendarAllDay(first)).assertIsDisplayed().assertTextEquals(summary)

        compose.onNodeWithTag(UiTags.CALENDAR_NEXT).performClick()
        compose.onNodeWithTag(UiTags.calendarDayView(first.plusDays(1))).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.calendarAllDay(first.plusDays(1))).assertDoesNotExist()
    }
}

package com.maggie.app.ui.screens.fullcalendar

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.screentest.FakeCalendar
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.screentest.Seed
import com.maggie.app.ui.UiTags
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A multi-day event is drawn on every day it crosses (MAG-204), without an
 * emulator (MAG-242).
 *
 * This is what `03-calendar-multi-day` asserted, and the two regressions it
 * holds:
 *
 * - `76017dd`: the day view filtered on the start date only, so an event ending
 *   the next morning was missing from the second day;
 * - `dfad086`: the week view drew it only in its start column, and the event
 *   expander dropped it from the week where it *ends* when it started in the one
 *   before.
 *
 * It runs through the screen and not through `WeekTimelineView` alone, because
 * the second half of `dfad086` is in `EventExpander`, under the ViewModel: the
 * screen is the shortest path that has both.
 *
 * **It is stronger than the flow it replaces.** « Train de nuit pour Vienne » was
 * seeded five days out, so whether its two days shared a week depended on the
 * weekday the suite ran on — the straddling case, which is the regression, was
 * seen on some days and not others. Here both cases are written down and both run
 * on every pull request.
 *
 * What is not asserted, as in the flow: the vertical position of the block in the
 * day view. It is drawn from the start hour to the end hour of *that* day
 * whatever the event's own times, so a night event lands at 21:00 on its second
 * day — a known quirk, and not what this is about.
 */
@RunWith(AndroidJUnit4::class)
class MultiDayEventScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private val train = "Train de nuit pour Vienne"

    @Test
    fun `a multi-day event inside one week is one bar across both of its days`() {
        val first = Seed.monday.plusDays(1)
        val last = first.plusDays(1)
        val fake = FakeCalendar(Seed.trainInsideTheWeek, Seed.lunchWithAlex)
        compose.setContent { FullCalendarScreen(viewModel = fake.viewModel) }

        weekOf(fake, Seed.monday)

        // One bar, tagged with the first and the last day it covers in this week:
        // the dates say which days are drawn, the text says which event.
        compose.onNodeWithTag(UiTags.calendarSpan(first, last))
            .assertIsDisplayed()
            .assertTextEquals(train)
    }

    /**
     * The `dfad086` case: the bar is cut at Sunday and the next week draws the
     * rest. Both halves are required — the event must be in the week it ends in,
     * which is the week it did *not* start in.
     */
    @Test
    fun `a multi-day event that straddles a Sunday is drawn in both weeks`() {
        val sunday = Seed.monday.plusDays(6)
        val nextMonday = sunday.plusDays(1)
        val fake = FakeCalendar(Seed.trainAcrossTheWeekend)
        compose.setContent { FullCalendarScreen(viewModel = fake.viewModel) }

        weekOf(fake, Seed.monday)
        compose.onNodeWithTag(UiTags.calendarSpan(sunday, sunday))
            .assertIsDisplayed()
            .assertTextEquals(train)

        // The way a person follows it: the toolbar's « next » arrow.
        compose.onNodeWithTag(UiTags.CALENDAR_NEXT).performClick()

        compose.onNodeWithTag(UiTags.calendarDay(nextMonday)).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.calendarSpan(nextMonday, nextMonday))
            .assertIsDisplayed()
            .assertTextEquals(train)
    }

    /**
     * The day view of the second day, opened the way a person does: tap the day's
     * header in the week view. The block sits below the fold — the timeline is 17
     * hours of 60 dp — so it is scrolled to, which is what the flow did too.
     */
    @Test
    fun `tapping a day header opens that day, with the event that reaches into it`() {
        val first = Seed.monday.plusDays(1)
        val last = first.plusDays(1)
        val fake = FakeCalendar(Seed.trainInsideTheWeek)
        compose.setContent { FullCalendarScreen(viewModel = fake.viewModel) }

        weekOf(fake, Seed.monday)
        compose.onNodeWithTag(UiTags.calendarDay(last)).performClick()

        compose.onNodeWithTag(UiTags.calendarEvent(last))
            .performScrollTo()
            .assertIsDisplayed()
            .assertTextEquals(train)

        // And on its first day as well, which is the half `76017dd` never broke —
        // asserted so that a fix going the other way does not pass.
        fake.viewModel.navigateToDate(first)
        compose.onNodeWithTag(UiTags.calendarEvent(first))
            .performScrollTo()
            .assertTextEquals(train)
    }

    /**
     * The week the journey pinned with the `Semaine` switch and `calendar_next`.
     * Driven through the ViewModel rather than through the toolbar: which week the
     * screen opens on is `goToToday`'s business and has its own test, and a flow
     * that had to walk there was paying for a device, not asserting anything.
     */
    private fun weekOf(fake: FakeCalendar, monday: java.time.LocalDate) {
        fake.viewModel.navigateToDate(monday)
        fake.viewModel.setViewType(CalendarViewType.WEEK)
        compose.onNodeWithTag(UiTags.calendarDay(monday)).assertIsDisplayed()
    }
}

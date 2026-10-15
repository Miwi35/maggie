package com.maggie.app.ui

import com.maggie.app.data.model.RecurringFrequency
import com.maggie.app.data.model.apiValue
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The calendar ids are built here and spelled by hand by whatever addresses the
 * app: `calendar_day_${TODAY}` in `e2e/mobile/subflows/open-calendar.yaml`,
 * `calendar_span_.*` in `e2e/mobile/flows/04-calendar-import.yaml`, and
 * `UiTags.calendarSpan(first, last)` / `UiTags.calendarEvent(day)` in
 * `MultiDayEventScreenTest` (MAG-242). A change of format is a change to all of
 * them, and this is where it fails first.
 */
class UiTagsTest {

    @Test
    fun `a day cell is addressed by its ISO date`() {
        assertEquals("calendar_day_2026-10-06", UiTags.calendarDay(LocalDate.of(2026, 10, 6)))
    }

    @Test
    fun `a span bar carries the first and the last day it covers`() {
        assertEquals(
            "calendar_span_2026-10-06_2026-10-07",
            UiTags.calendarSpan(LocalDate.of(2026, 10, 6), LocalDate.of(2026, 10, 7)),
        )
    }

    @Test
    fun `a day view event is addressed by the day shown`() {
        assertEquals("calendar_event_2026-10-07", UiTags.calendarEvent(LocalDate.of(2026, 10, 7)))
    }

    @Test
    fun `a drawer entry and a Google import button are suffixed by their key`() {
        assertEquals("drawer_calendar", UiTags.drawerItem("calendar"))
        assertEquals("google_import_e2e@maggie.local", UiTags.googleImport("e2e@maggie.local"))
    }

    /** The flows address the drawer through the constant; the drawer builds the tag from the route. */
    @Test
    fun `the declared drawer tag is the one the drawer builds for its route`() {
        assertEquals(UiTags.DRAWER_GROCERY, UiTags.drawerItem("grocery"))
    }

    /** The rail carries the drawer's destinations under its own prefix (MAG-35), so the two never collide. */
    @Test
    fun `a rail entry is suffixed by its route, under its own prefix`() {
        assertEquals("rail_grocery", UiTags.railItem("grocery"))
        assertEquals("rail_settings", UiTags.railItem("settings"))
    }

    /** `recurring_option_weekly` in `e2e/mobile/flows/15-recurring-grocery.yaml` is the API's spelling of the frequency. */
    @Test
    fun `a recurring option is suffixed by the frequency's API value`() {
        assertEquals("recurring_option_weekly", UiTags.recurringOption(RecurringFrequency.WEEKLY.apiValue()))
        assertEquals("recurring_option_biweekly", UiTags.recurringOption(RecurringFrequency.BIWEEKLY.apiValue()))
        assertEquals("recurring_option_none", UiTags.recurringOption("none"))
    }

    @Test
    fun `a savings advance's button is suffixed by the advance`() {
        assertEquals("savings_advance_done_adv-1", UiTags.savingsAdvanceDone("adv-1"))
    }

    /** `budget_node_<category name>`: a tablet journey taps « Obsydian » by its name (MAG-419). */
    @Test
    fun `a budget tree node is suffixed by its category's name`() {
        assertEquals("budget_node_Obsydian", UiTags.budgetNode("Obsydian"))
    }
}

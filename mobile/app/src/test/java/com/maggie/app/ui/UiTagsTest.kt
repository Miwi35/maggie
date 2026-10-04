package com.maggie.app.ui

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The calendar ids are built here and spelled by hand in `e2e/mobile/flows/` —
 * `calendar_span_${TRAIN_START}_.*` — so a change of format is a change of flow.
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
}

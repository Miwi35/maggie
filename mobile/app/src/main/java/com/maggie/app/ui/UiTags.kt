package com.maggie.app.ui

import java.time.LocalDate

/**
 * The ids the Maestro journeys address the app by (MAG-98).
 *
 * Maestro reads the view hierarchy through UiAutomator, where a Compose node has
 * no resource id of its own — unless its semantics root opts in. That opt-in is
 * [uiTagRoot], and it goes on **every window**, not once on the activity: a
 * `Dialog` or a `ModalBottomSheet` is a separate semantics owner, and a tag added
 * inside one without a root of its own is invisible to Maestro. A `testTag` from
 * this file is then what `id: "…"` matches in `e2e/mobile/flows/`.
 *
 * Why tags and not the visible text: a journey anchored on « Demander à
 * Maggie... » breaks the day the placeholder is reworded, and the failure reads
 * as a broken chat rather than as a renamed string. Tags also disambiguate what
 * the screen says twice — that placeholder is both the collapsed bar and the
 * sheet's text field.
 *
 * Renaming one here means renaming it in the flows: `task e2e:mobile:lint` checks
 * that every id a flow uses is declared below, and that every window carrying one
 * of these has its own [uiTagRoot] — so both mistakes fail in seconds instead of
 * on an emulator.
 *
 * A tag ending in `_PREFIX` is not an id on its own: the calendar needs « this
 * event, on this day » to be addressable, so those ids are a prefix followed by
 * an ISO date, built by the helpers below. A flow spells them with the date it
 * computed (`calendar_day_${DAY}`) or a regex (`calendar_span_.*`), and the lint
 * accepts any id that starts with a declared prefix.
 */
object UiTags {
    /** Login — the single button the screen offers, whatever door is behind it. */
    const val LOGIN_SIGN_IN = "login_sign_in"

    /** Dashboard — the root, so a journey can wait for the screen itself. */
    const val DASHBOARD = "dashboard"

    /** The collapsed chat bar, on every main screen. */
    const val CHAT_OPEN = "chat_open"

    /** The microphone, which opens the chat sheet in voice mode. */
    const val CHAT_MIC = "chat_mic"

    /** The brain, which opens the context sheet. */
    const val CHAT_CONTEXTS = "chat_contexts"

    /** The chat sheet: its input and its two buttons. */
    const val CHAT_INPUT = "chat_input"
    const val CHAT_SEND = "chat_send"
    const val CHAT_CLOSE = "chat_close"

    /** The voice bar's state line — « Maggie parle... » and friends. */
    const val VOICE_STATE = "voice_state"

    /** Top bar — the burger that opens the navigation drawer. */
    const val NAV_MENU = "nav_menu"

    /** Navigation drawer — one entry per destination, suffixed by its route. */
    const val DRAWER_ITEM_PREFIX = "drawer_"

    /** Finance dashboard — one access per part of the module, suffixed by its route. */
    const val FINANCE_ACCESS_PREFIX = "finance_access_"

    /** Calendar toolbar — the « next » arrow and the Semaine switch. */
    const val CALENDAR_NEXT = "calendar_next"
    const val CALENDAR_VIEW_WEEK = "calendar_view_week"

    /** Calendar — the options menu and its Google import entry. */
    const val CALENDAR_OPTIONS = "calendar_options"
    const val CALENDAR_IMPORT_GOOGLE = "calendar_import_google"

    /** Google import dialog — the « Importer » button of one calendar, suffixed by its Google id. */
    const val GOOGLE_IMPORT_PREFIX = "google_import_"

    /** Week view — a day's header cell, suffixed by the ISO date it stands for. */
    const val CALENDAR_DAY_PREFIX = "calendar_day_"

    /**
     * Week view — the bar of an all-day or multi-day event, suffixed by the first
     * and last day it covers **in the displayed week** (`<first>_<last>`, ISO
     * dates). A bar that continues past Sunday ends on Sunday, and the next week
     * starts a new bar on Monday: the dates say which days are drawn, and the
     * visible text says which event.
     */
    const val CALENDAR_SPAN_PREFIX = "calendar_span_"

    /** Day view — a timed event block, suffixed by the ISO date of the day shown. */
    const val CALENDAR_EVENT_PREFIX = "calendar_event_"

    fun drawerItem(route: String) = DRAWER_ITEM_PREFIX + route

    fun financeAccess(route: String) = FINANCE_ACCESS_PREFIX + route

    fun googleImport(calendarId: String) = GOOGLE_IMPORT_PREFIX + calendarId

    fun calendarDay(date: LocalDate) = CALENDAR_DAY_PREFIX + date

    fun calendarSpan(first: LocalDate, last: LocalDate) = "$CALENDAR_SPAN_PREFIX${first}_$last"

    fun calendarEvent(date: LocalDate) = CALENDAR_EVENT_PREFIX + date
}

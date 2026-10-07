package com.maggie.app.data.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/**
 * The reminders of an event, in Google's shape — the one the API stores and the
 * reminder cron reads.
 *
 * `overrides` is the only key anything looks at: a bare list of reminders stores
 * fine and fires nothing (MAG-121), so the app never builds the field by hand —
 * [remindersOf] and [remindersFrom] are the two directions, and a delay in
 * minutes is what the screens speak.
 */
// The app's Json does not encode defaults: without @EncodeDefault, `useDefault` and `method` never left the phone,
// and the API refused the event (« reminders[overrides][0][method]: This field is missing », 7 Oct.).
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class EventReminders(
    @EncodeDefault val useDefault: Boolean = false,
    val overrides: List<EventReminder> = emptyList(),
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class EventReminder(
    @EncodeDefault val method: String = "popup",
    val minutes: Int,
)

/** The delays an event holds, in minutes before the start. What the cron ignores reads as none. */
fun remindersOf(reminders: EventReminders?): List<Int> =
    reminders?.overrides?.map { it.minutes }?.filter { it > 0 } ?: emptyList()

/** Google's shape for a list of delays, or null — which is what clears the field. */
fun remindersFrom(minutes: List<Int>): EventReminders? =
    if (minutes.isEmpty()) {
        null
    } else {
        EventReminders(useDefault = false, overrides = minutes.map { EventReminder(minutes = it) })
    }

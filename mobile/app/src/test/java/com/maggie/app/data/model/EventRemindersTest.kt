package com.maggie.app.data.model

import com.maggie.app.data.local.entity.EventEntity
import com.maggie.app.ui.screens.shared.remindersText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The reminders of an event, from the API to the screen and back (MAG-121).
 *
 * `overrides` is the only key the reminder cron reads, so a bare list stores fine
 * and fires nothing. The three things worth pinning are here: the shape the app
 * builds, what it reads back, and the Room round trip — the cache keeps the JSON
 * itself, so a reminder that does not survive it is one the owner stops seeing
 * the moment the app reopens offline.
 */
class EventRemindersTest {

    private val event = Event(
        id = "e1",
        summary = "Dentiste",
        startAt = "2026-10-12T10:00:00Z",
        endAt = "2026-10-12T11:00:00Z",
    )

    @Test
    fun `a delay becomes Google's shape`() {
        assertEquals(
            EventReminders(useDefault = false, overrides = listOf(EventReminder("popup", 60))),
            remindersFrom(listOf(60)),
        )
    }

    @Test
    fun `no delay is no reminders, which is what clears the field`() {
        assertNull(remindersFrom(emptyList()))
    }

    @Test
    fun `reading back ignores what the cron ignores`() {
        assertEquals(emptyList<Int>(), remindersOf(null))
        assertEquals(emptyList<Int>(), remindersOf(EventReminders(useDefault = true)))
        // Zero means "no reminder" to the cron, so it is not one here either.
        assertEquals(emptyList<Int>(), remindersOf(remindersFrom(listOf(0))))
        assertEquals(listOf(10, 60), remindersOf(remindersFrom(listOf(10, 60))))
    }

    @Test
    fun `the cache keeps the reminders through a round trip`() {
        val stored = EventEntity.fromModel(event.copy(reminders = remindersFrom(listOf(30, 1440))))

        assertEquals(listOf(30, 1440), remindersOf(stored.toModel().reminders))
    }

    @Test
    fun `an event with no reminder stores none`() {
        val stored = EventEntity.fromModel(event)

        assertNull(stored.reminders)
        assertNull(stored.toModel().reminders)
    }

    @Test
    fun `the event card says the reminders in French`() {
        assertEquals("30 minutes avant, 1 jour avant", remindersText(remindersFrom(listOf(30, 1440))))
        assertNull(remindersText(null))
    }
}

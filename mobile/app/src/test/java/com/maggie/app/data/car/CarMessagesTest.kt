package com.maggie.app.data.car

import com.maggie.app.data.fcm.PushPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** In the car every message leaves as a conversation; one that waits is told again once, five minutes later (MAG-683). */
class CarMessagesTest {

    private var connected = true
    private val posted = mutableListOf<Pair<String, Boolean>>()
    private val tray = mutableSetOf<String>()
    private val scheduled = mutableListOf<Pair<String, Long>>()
    private val cancelled = mutableListOf<String>()

    private val car = CarMessages(
        isConnected = { connected },
        post = { payload, nudge ->
            posted += payload.notificationId to nudge
            tray += payload.systemTag
        },
        isShowing = { it in tray },
        scheduleNudge = { payload, delayMs -> scheduled += payload.notificationId to delayMs },
        cancelNudge = { cancelled += it.notificationId },
    )

    private fun message(id: String, type: String = "proaction", vararg extra: Pair<String, String>) =
        PushPayload.from(mapOf("notificationId" to id, "type" to type, "title" to "Titre $id") + extra)!!

    private fun reminder(id: String) = message(id, "task_nudge", "reminderId" to "r-$id", "actions" to "done,in10,tonight")

    private fun approval(id: String) = message(id, "approval", "approvalId" to "ap-$id")

    /** What the alarm does when it fires: the clock is the test's, it never runs by itself. */
    private fun fireAlarm(id: String) = car.nudge(waitingMessages.getValue(id))

    private val waitingMessages = mutableMapOf<String, PushPayload>()

    private fun say(payload: PushPayload): Boolean {
        waitingMessages[payload.notificationId] = payload
        return car.say(payload)
    }

    @Test
    fun `out of the car nothing is said and the phone does what it always did`() {
        connected = false

        assertFalse(say(reminder("a")))

        assertTrue(posted.isEmpty())
        assertTrue(scheduled.isEmpty())
    }

    @Test
    fun `in the car a message leaves as a notification, whatever it is`() {
        assertTrue(say(message("a")))
        assertTrue(say(reminder("b")))
        assertTrue(say(approval("c")))

        assertEquals(listOf("a", "b", "c").map { it to false }, posted)
    }

    @Test
    fun `a message that waits for an answer is told again once, five minutes later`() {
        say(reminder("a"))
        say(approval("b"))

        assertEquals(listOf("a" to 300_000L, "b" to 300_000L), scheduled)

        fireAlarm("a")

        assertEquals(listOf("a" to false, "b" to false, "a" to true), posted)
        assertEquals("the alarm is not set again: the reminder is the only one", 2, scheduled.size)
    }

    @Test
    fun `the alarm firing again does not say it a third time`() {
        say(reminder("a"))
        fireAlarm("a")
        fireAlarm("a")

        assertEquals(1, scheduled.size)
        assertEquals(listOf(false, true), posted.map { it.second })
    }

    @Test
    fun `a message that waits for nothing is not told again`() {
        say(message("a"))
        say(message("departure", "departure"))
        say(message("ack", "reminder", "interaction" to """{"kind":"ack"}"""))

        assertTrue(scheduled.isEmpty())
    }

    @Test
    fun `the same message arriving twice is said once and nudged from its first arrival`() {
        say(reminder("a"))
        say(reminder("a"))

        assertEquals(1, scheduled.size)
        assertEquals(listOf("a" to false), posted)
    }

    @Test
    fun `answered, read or opened in the meantime it is left alone`() {
        say(reminder("a"))
        tray -= "a"

        fireAlarm("a")

        assertEquals(listOf("a" to false), posted)
    }

    @Test
    fun `the car gone, the phone took the message back and the alarm leaves it alone`() {
        say(reminder("a"))
        connected = false
        car.left()
        tray -= "a"

        fireAlarm("a")

        assertEquals(listOf("a" to false), posted)
    }

    @Test
    fun `a message forgotten is not nudged and its alarm is cancelled`() {
        say(reminder("a"))

        car.forget("a")
        car.forget("unknown")

        assertEquals(listOf("a"), cancelled)
        assertTrue("it is no longer given back", car.left().isEmpty())
    }

    @Test
    fun `leaving the car gives back what is still waiting, and only that`() {
        say(reminder("a"))
        say(approval("b"))
        say(message("c"))
        tray -= "a"
        connected = false

        val back = car.left()

        assertEquals(listOf("ap-b"), back.map { it.approvalId })
        assertEquals("their alarms are cancelled", setOf("a", "b"), cancelled.toSet())
        assertTrue("given back once", car.left().isEmpty())
    }
}

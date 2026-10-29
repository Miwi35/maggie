package com.maggie.app.data.interruption

import com.maggie.app.data.fcm.PushChannels
import com.maggie.app.data.fcm.PushInteraction
import com.maggie.app.data.fcm.PushPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What Maggie says while the app is open: one at a time, validations first, never twice (MAG-314). */
class InterruptionCenterTest {

    private var clock = 1_000L
    private val center = InterruptionCenter(now = { clock })

    private fun message(id: String, type: String = "proaction", interaction: String? = null) = PushPayload.from(
        buildMap {
            put("notificationId", id)
            put("type", type)
            put("title", "Titre $id")
            interaction?.let { put("interaction", """{"kind":"$it"}""") }
        },
    )!!

    private fun question(id: String) = PushPayload(
        notificationId = "n-$id",
        type = "approval",
        channel = PushChannels.APPROVALS,
        title = "Valider",
        text = null,
        link = null,
        actionLabel = null,
        approvalId = id,
        interaction = PushInteraction(kind = "confirm"),
    )

    private fun shown() = center.current.value?.notificationId

    @Test
    fun `nothing is said until something is offered`() {
        assertNull(center.current.value)
    }

    @Test
    fun `the first message is shown at once and the next ones wait their turn`() {
        center.offer(message("a"))
        center.offer(message("b"))
        center.offer(message("c"))

        assertEquals("a", shown())
        center.close("a")
        assertEquals("b", shown())
        center.close("b")
        assertEquals("c", shown())
        center.close("c")
        assertNull(center.current.value)
    }

    @Test
    fun `a validation goes ahead of the messages waiting, but not ahead of the one on screen`() {
        center.offer(message("a"))
        center.offer(message("b"))
        center.offer(message("q1", interaction = "confirm"))
        center.offer(message("q2", interaction = "confirm"))

        assertEquals("a", shown())
        center.close("a")
        assertEquals("q1", shown())
        center.close("q1")
        assertEquals("q2", shown())
        center.close("q2")
        assertEquals("b", shown())
    }

    @Test
    fun `a held action never takes the screen, it waits in the bell and the chat`() {
        center.offer(question("q1"))

        assertNull(center.current.value)
        center.offer(message("a"))
        assertEquals("a", shown())
    }

    @Test
    fun `a question with answers to choose from stays listed after its timeout and goes once answered`() {
        val choice = message("c", type = "reminder").copy(interaction = PushInteraction(kind = "choice", options = listOf("Oui", "Non")))
        center.offer(choice)

        center.timeOut("c")
        assertNull(center.current.value)
        assertEquals(listOf("c"), center.questions.value.map { it.notificationId })

        center.close("c")
        assertTrue(center.questions.value.isEmpty())
    }

    @Test
    fun `a confirm form is a question too`() {
        center.offer(message("a"))
        center.offer(message("b"))
        center.offer(message("c", interaction = "confirm"))

        center.close("a")

        assertEquals("c", shown())
    }

    @Test
    fun `the same message arriving by push and by Mercure is shown once`() {
        assertTrue(center.offer(message("a")))
        assertFalse(center.offer(message("a")))
        center.close("a")

        assertFalse("answered or timed out, it does not come back", center.offer(message("a")))
        assertNull(center.current.value)
    }

    @Test
    fun `a held action is the same question whichever notification announces it`() {
        assertTrue(center.offer(question("q1")))
        assertFalse(center.offer(question("q1").copy(notificationId = "other")))
    }

    @Test
    fun `an approval notification without its approval is left to the approvals feed`() {
        assertFalse(center.offer(message("a", type = "approval")))
        assertNull(center.current.value)
    }

    @Test
    fun `a message read elsewhere before it was shown never appears`() {
        center.offer(message("a"))
        center.close("b")
        center.offer(message("b"))

        assertEquals("a", shown())
        center.close("a")
        assertNull(center.current.value)
    }

    @Test
    fun `a message waiting is dropped when it is withdrawn`() {
        center.offer(message("a"))
        center.offer(message("b"))
        center.offer(message("c"))

        center.close("b")
        center.close("a")

        assertEquals("c", shown())
    }

    @Test
    fun `Plus tard removes it now and lets it be offered again when it comes back`() {
        center.offer(message("a"))
        center.offer(message("b"))

        center.postpone("a")

        assertEquals("b", shown())
        center.close("b")
        assertTrue(center.offer(message("a"), reshow = true))
        assertEquals("a", shown())
    }

    @Test
    fun `a postponed message is not brought back by a feed before its time`() {
        center.offer(message("a"))
        center.postpone("a", delayMs = 600_000)

        assertFalse("the feed re-offers what is pending each time the app opens", center.offer(message("a")))
        clock += 599_999
        assertFalse(center.offer(message("a")))
        assertNull(shown())

        clock += 1
        assertTrue(center.offer(message("a")))
        assertEquals("a", shown())
    }

    @Test
    fun `the alarm of a postponed message brings it back whatever the time`() {
        center.offer(message("a"))
        center.postpone("a", delayMs = 600_000)

        assertTrue(center.offer(message("a"), reshow = true))
        assertEquals("a", shown())
        assertFalse("and it is not shown twice", center.offer(message("a")))
    }

    @Test
    fun `signing out forgets everything said to the account`() {
        center.offer(message("a"))
        center.offer(message("b"))
        center.postpone("b")
        center.announce("a")

        center.clear()

        assertNull(shown())
        assertTrue(center.announce("a"))
        assertTrue(center.offer(message("a")))
        assertTrue(center.offer(message("b")))
        center.close("a")
        assertEquals("b", shown())
    }

    @Test
    fun `an interruption is announced once whichever screen shows it`() {
        assertTrue(center.announce("a"))
        assertFalse(center.announce("a"))
        assertTrue(center.announce("b"))
    }

    @Test
    fun `a tap on a notification shows it now and the one it replaces waits`() {
        center.offer(message("a"))
        center.offer(message("b"))

        center.reopen(message("z"))

        assertEquals("z", shown())
        center.close("z")
        assertEquals("a", shown())
        center.close("a")
        assertEquals("b", shown())
    }

    @Test
    fun `reopening what is already on screen changes nothing`() {
        center.offer(message("a"))
        center.offer(message("b"))

        center.reopen(message("a"))
        center.close("a")

        assertEquals("b", shown())
    }

    @Test
    fun `reopening a message that waits does not leave it twice`() {
        center.offer(message("a"))
        center.offer(message("b"))

        center.reopen(message("b"))
        center.close("b")
        assertEquals("a", shown())
        center.close("a")

        assertNull(center.current.value)
    }

    @Test
    fun `a reopened message can be answered then reopened again`() {
        center.offer(message("a"))
        center.close("a")

        center.reopen(message("a"))

        assertEquals("a", shown())
    }

    // --- The Cuisine activity says its timers' ends itself (MAG-582)

    @Test
    fun `a timer's end is not raised while the activity holds it, and not later either`() {
        center.hold("cooking_timer")

        assertFalse(center.offer(message("n-1", type = "cooking_timer")))
        assertNull(shown())

        center.release("cooking_timer")
        assertFalse("already said by the activity", center.offer(message("n-1", type = "cooking_timer")))
        assertTrue(center.offer(message("n-2", type = "cooking_timer")))
        assertEquals("n-2", shown())
    }

    @Test
    fun `the hold is on the timers' ends only`() {
        center.hold("cooking_timer")

        assertTrue(center.offer(message("n-1", type = "proaction")))
        assertEquals("n-1", shown())
    }

    @Test
    fun `a tap on a timer's end does not open it over the activity`() {
        center.hold("cooking_timer")

        center.reopen(message("n-1", type = "cooking_timer"))

        assertNull(shown())
    }

    @Test
    fun `two holders, one release, still held`() {
        center.hold("cooking_timer")
        center.hold("cooking_timer")
        center.release("cooking_timer")

        assertFalse(center.offer(message("n-1", type = "cooking_timer")))
    }
}

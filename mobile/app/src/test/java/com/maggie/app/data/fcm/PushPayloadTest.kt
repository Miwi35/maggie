package com.maggie.app.data.fcm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The `data` block of `PushMessageFactory` read as the app reads it, and the buttons it earns. */
class PushPayloadTest {

    private fun payload(
        type: String? = null,
        vararg extra: Pair<String, String>,
        channel: String? = null,
    ): PushPayload = PushPayload.from(
        data = buildMap {
            put("notificationId", "n-1")
            type?.let { put("type", it) }
            putAll(extra)
        },
        notificationChannel = channel,
    )!!

    @Test
    fun `a message without a notification id is not a notification of ours`() {
        assertNull(PushPayload.from(mapOf("type" to "reminder", "title" to "Dentiste")))
    }

    @Test
    fun `the text is read from body, as the server sends it, or from text`() {
        assertEquals("Dans 10 min", payload("reminder", "body" to "Dans 10 min").text)
        assertEquals("Dans 10 min", payload("reminder", "text" to "Dans 10 min").text)
    }

    @Test
    fun `the title falls back to the notification block and then to Maggie`() {
        val fromBlock = PushPayload.from(mapOf("notificationId" to "n-1"), notificationTitle = "Rappel")!!
        assertEquals("Rappel", fromBlock.title)
        assertEquals("Maggie", payload().title)
    }

    @Test
    fun `the channel is the one the server named, else the one of the type`() {
        assertEquals(PushChannels.FINANCE, payload("reminder", channel = "finance").channel)
        assertEquals(PushChannels.REMINDERS, payload("reminder").channel)
        assertEquals(PushChannels.REMINDERS, payload("task_due").channel)
        assertEquals(PushChannels.APPROVALS, payload("approval").channel)
        assertEquals(PushChannels.FINANCE, payload("consent_expiring").channel)
        assertEquals(PushChannels.CHAT, payload("proaction").channel)
    }

    @Test
    fun `a channel the app does not create is replaced by the type's`() {
        assertEquals(PushChannels.REMINDERS, payload("reminder", channel = "promotions").channel)
    }

    @Test
    fun `an empty link or label is no link and no label`() {
        val p = payload("reminder", "link" to "", "actionLabel" to "")
        assertNull(p.link)
        assertNull(p.actionLabel)
    }

    @Test
    fun `a reminder is acknowledged with OK`() {
        assertEquals(listOf(PushActionKind.OK), payload("reminder").actions())
    }

    @Test
    fun `a task that falls due is Fait or Plus tard`() {
        assertEquals(listOf(PushActionKind.DONE, PushActionKind.LATER), payload("task_due").actions())
    }

    @Test
    fun `a chat message is answered`() {
        assertEquals(listOf(PushActionKind.REPLY), payload("proaction").actions())
    }

    @Test
    fun `a message with a link and nothing else to do is Y aller or Plus tard`() {
        val p = payload("finance", "link" to "maggie://finance/banks")
        assertEquals(listOf(PushActionKind.GO, PushActionKind.LATER), p.actions())
    }

    @Test
    fun `an approval is Autoriser or Refuser, once it names the held action`() {
        assertEquals(
            listOf(PushActionKind.APPROVE, PushActionKind.DENY),
            payload("approval", "approvalId" to "ap-1").actions(),
        )
        assertEquals(emptyList<PushActionKind>(), payload("approval").actions())
    }

    @Test
    fun `the interaction the server declares wins over the type`() {
        val ack = payload(
            "reminder",
            "interaction" to """{"kind":"ack","completable":true}""",
        )
        assertEquals(listOf(PushActionKind.DONE, PushActionKind.LATER), ack.actions())

        val go = payload(
            "reminder",
            "link" to "maggie://event/e-1",
            "interaction" to """{"kind":"action"}""",
        )
        assertEquals(listOf(PushActionKind.GO, PushActionKind.LATER), go.actions())

        val confirm = payload(
            "proaction",
            "approvalId" to "ap-1",
            "interaction" to """{"kind":"confirm"}""",
        )
        assertEquals(listOf(PushActionKind.APPROVE, PushActionKind.DENY), confirm.actions())
    }

    @Test
    fun `a choice has no button, tapping opens the request`() {
        val p = payload(
            "proaction",
            "link" to "maggie://chat",
            "interaction" to """{"kind":"choice","options":["a","b","c","d"]}""",
        )
        assertEquals(emptyList<PushActionKind>(), p.actions())
    }

    @Test
    fun `an unreadable interaction is ignored, the type decides`() {
        assertEquals(listOf(PushActionKind.OK), payload("reminder", "interaction" to "{not json").actions())
    }
}

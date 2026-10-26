package com.maggie.app.data.fcm

import com.maggie.app.ui.navigation.DeepLinks
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
        assertEquals(PushChannels.FINANCE, payload("reminder", channel = PushChannels.FINANCE).channel)
        assertEquals(PushChannels.REMINDERS, payload("reminder").channel)
        assertEquals(PushChannels.REMINDERS, payload("task_due").channel)
        assertEquals(PushChannels.APPROVALS, payload("approval").channel)
        assertEquals(PushChannels.FINANCE, payload("consent_expiring").channel)
        assertEquals(PushChannels.CHAT, payload("proaction").channel)
    }

    @Test
    fun `a channel of the previous generation is replaced by the type's`() {
        assertEquals(PushChannels.REMINDERS, payload("reminder", channel = "chat").channel)
        assertEquals(PushChannels.CHAT, payload("proaction", channel = "chat").channel)
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
    fun `a reminder without a link is acknowledged with C'est noté`() {
        assertEquals(listOf(PushActionKind.OK), payload("reminder").actions())
    }

    @Test
    fun `a reminder with a link carries C'est noté, the link and Plus tard`() {
        val p = payload("reminder", "link" to "maggie://event/e-1", "actionLabel" to "Voir l'événement")
        assertEquals(listOf(PushActionKind.OK, PushActionKind.GO, PushActionKind.LATER), p.actions())
    }

    @Test
    fun `C'est noté is the only name of the acknowledgement`() {
        assertEquals("C'est noté", PushActionKind.OK.label)
    }

    @Test
    fun `a task that falls due with a link keeps Fait and Plus tard`() {
        val p = payload("task_due", "link" to "maggie://task/t-1")
        assertEquals(listOf(PushActionKind.DONE, PushActionKind.LATER), p.actions())
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
    fun `a message with a link and nothing else to do is C'est noté, Y aller or Plus tard`() {
        val p = payload("finance", "link" to "maggie://finance/banks")
        assertEquals(listOf(PushActionKind.OK, PushActionKind.GO, PushActionKind.LATER), p.actions())
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
        assertEquals(listOf(PushActionKind.OK, PushActionKind.GO, PushActionKind.LATER), go.actions())

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

    // MAG-510: a task reminder carries the buttons the server lists, in its order.

    private fun reminder(type: String, actions: String?) = payload(
        type,
        *listOfNotNull("reminderId" to "r-1", "taskId" to "t-1", actions?.let { "actions" to it }).toTypedArray(),
    )

    @Test
    fun `a task nudge rings on the reminders channel`() {
        assertEquals(PushChannels.REMINDERS, payload("task_nudge").channel)
    }

    @Test
    fun `a task reminder reads its reminder, its task and its buttons`() {
        val p = reminder("task_nudge", "start,in10,tonight")

        assertEquals("r-1", p.reminderId)
        assertEquals("t-1", p.taskId)
        assertEquals(listOf("start", "in10", "tonight"), p.reminderActions)
    }

    @Test
    fun `a nudge's buttons come in the server's order with their labels`() {
        val p = reminder("task_nudge", "start,in10,tonight")

        assertEquals(listOf(PushActionKind.START, PushActionKind.IN_TEN, PushActionKind.TONIGHT), p.actions())
        assertEquals(listOf("Je m'y mets", "Dans 10 min", "Ce soir"), p.actions().map { it.label })
    }

    @Test
    fun `a due reminder's buttons replace Fait and Plus tard`() {
        val p = reminder("task_due", "start,done,tomorrow")

        assertEquals(listOf(PushActionKind.START, PushActionKind.TICK, PushActionKind.TOMORROW), p.actions())
        assertEquals(listOf("Je m'y mets", "Fait", "Demain"), p.actions().map { it.label })
    }

    @Test
    fun `no more than three buttons, the most Android shows, and an unknown key is skipped`() {
        assertEquals(
            listOf(PushActionKind.TOMORROW, PushActionKind.START, PushActionKind.IN_TEN),
            reminder("task_due", "tomorrow, snooze_week ,start,in10,done").actions(),
        )
    }

    @Test
    fun `a reminder whose notification has no button shows none`() {
        assertEquals(emptyList<PushActionKind>(), reminder("task_due", null).actions())
    }

    @Test
    fun `buttons without a reminder to answer are not reminder buttons`() {
        val p = payload("task_due", "actions" to "start,done,tomorrow")

        assertEquals(listOf(PushActionKind.DONE, PushActionKind.LATER), p.actions())
    }

    @Test
    fun `Je m'y mets points at the Focus of the reminder's task, never at a forged id`() {
        assertEquals("${DeepLinks.SCHEME}://task/t-1/focus", reminder("task_nudge", "start").focusLink())
        assertNull(payload("task_nudge", "reminderId" to "r-1").focusLink())
        assertNull(payload("task_nudge", "reminderId" to "r-1", "taskId" to "../users").focusLink())
    }

    // MAG-512: a routine reminder is a nudge of its own, « Voir » opening the tasks.

    @Test
    fun `a routine reminder rings on the reminders channel`() {
        assertEquals(PushChannels.REMINDERS, payload("routine").channel)
    }

    @Test
    fun `one routine is Fait, Dans 10 min or Voir`() {
        val p = reminder("routine", "done,in10,open")

        assertEquals(listOf(PushActionKind.TICK, PushActionKind.IN_TEN, PushActionKind.OPEN), p.actions())
        assertEquals(listOf("Fait", "Dans 10 min", "Voir"), p.actions().map { it.label })
    }

    @Test
    fun `several routines are Voir or Dans 10 min`() {
        val p = reminder("routine", "open,in10")

        assertEquals(listOf(PushActionKind.OPEN, PushActionKind.IN_TEN), p.actions())
        assertEquals(listOf("Voir", "Dans 10 min"), p.actions().map { it.label })
    }

    @Test
    fun `Voir is answered with the key open`() {
        assertEquals(PushActionKind.OPEN, PushActionKind.ofReminderKey("open"))
        assertEquals("open", PushActionKind.OPEN.reminderKey)
    }

    // MAG-570: a shortage alert asks « Ajouter à la liste » or « Plus tard ».

    private fun shortage(vararg extra: Pair<String, String>) = payload(
        "grocery",
        "shortageId" to "01HZX3K9P2M7Q4R8S5T6V0W1AB",
        "actions" to "add",
        *extra,
    )

    @Test
    fun `a shortage alert reads its id and carries Ajouter à la liste and Plus tard`() {
        val p = shortage()

        assertEquals("01HZX3K9P2M7Q4R8S5T6V0W1AB", p.shortageId)
        assertEquals(listOf(PushActionKind.ADD_TO_LIST, PushActionKind.LATER), p.actions())
        assertEquals(listOf("Ajouter à la liste", "Plus tard"), p.actions().map { it.label })
    }

    @Test
    fun `a shortage alert keeps its two buttons whatever link or interaction it carries`() {
        val p = shortage("link" to "maggie://grocery/x", "interaction" to """{"kind":"action"}""")

        assertEquals(listOf(PushActionKind.ADD_TO_LIST, PushActionKind.LATER), p.actions())
    }

    @Test
    fun `a grocery message without a shortage id keeps the default button, and a blank id is none`() {
        assertEquals(listOf(PushActionKind.REPLY), payload("grocery").actions())
        assertNull(payload("grocery", "shortageId" to "").shortageId)
    }

    @Test
    fun `a cooking timer's end carries its timer and goes by its tag`() {
        val payload = PushPayload.from(mapOf("notificationId" to "n-9", "type" to "cooking_timer", "title" to "Eau : c'est prêt", "cookingTimerId" to "t-1"))!!

        assertEquals("t-1", payload.cookingTimerId)
        assertEquals("cooking-timer-t-1", payload.systemTag)
        assertEquals(PushChannels.REMINDERS, payload.channel)
    }

    @Test
    fun `any other push goes by its notification id`() {
        val payload = PushPayload.from(mapOf("notificationId" to "n-1", "type" to "reminder", "title" to "Rappel"))!!

        assertEquals("n-1", payload.systemTag)
    }

    @Test
    fun `a location request is never a notification, with or without an id`() {
        assertNull(PushPayload.from(mapOf("type" to "location_request")))
        assertNull(PushPayload.from(mapOf("type" to "location_request", "notificationId" to "n-1", "title" to "Maggie")))
    }
}

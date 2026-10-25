package com.maggie.app.data.fcm

import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class PushNotifierTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val manager = app.getSystemService(NotificationManager::class.java)
    private val notifier = PushNotifier(app)

    @Before
    fun channels() = PushChannels.create(app)

    private fun payload(type: String, approvalId: String? = null, link: String? = null) = PushPayload.from(
        data = buildMap {
            put("notificationId", "n-1")
            put("type", type)
            put("title", "Titre")
            put("body", "Texte")
            approvalId?.let { put("approvalId", it) }
            link?.let { put("link", it) }
        },
    )!!

    private fun shown() = shadowOf(manager).getNotification("n-1", PushNotifier.NOTIFICATION_ID)

    private fun buttons() = shown().actions.orEmpty().map { it.title.toString() }

    @Test
    fun `with the permission refused nothing reaches the system and the app knows it`() {
        shadowOf(manager).setNotificationsEnabled(false)

        assertFalse(notifier.canNotify())
        assertFalse(notifier.show(payload("reminder")))
        assertEquals(0, shadowOf(manager).allNotifications.size)
    }

    @Test
    fun `with the permission granted the notification is shown on its channel under the server's tag`() {
        shadowOf(manager).setNotificationsEnabled(true)

        assertTrue(notifier.canNotify())
        assertTrue(notifier.show(payload("reminder")))

        val notification = shown()
        assertNotNull(notification)
        assertEquals(PushChannels.REMINDERS, notification.channelId)
        assertEquals("Titre", notification.extras.getString("android.title"))
        assertEquals("Texte", notification.extras.getString("android.text"))
    }

    @Test
    fun `a reminder carries C'est noté`() {
        notifier.show(payload("reminder"))
        assertEquals(listOf("C'est noté"), buttons())
    }

    @Test
    fun `a task falling due carries Fait and Plus tard`() {
        notifier.show(payload("task_due"))
        assertEquals(listOf("Fait", "Plus tard"), buttons())
    }

    @Test
    fun `a validation carries Autoriser and Refuser`() {
        notifier.show(payload("approval", approvalId = "ap-1"))

        assertEquals(listOf("Autoriser", "Refuser"), buttons())
        assertEquals(PushChannels.APPROVALS, shown().channelId)
    }

    @Test
    fun `a chat message carries Répondre, which takes typed text`() {
        notifier.show(payload("proaction"))

        val reply = shown().actions.single()
        assertEquals("Répondre", reply.title.toString())
        assertEquals(PushActionReceiver.KEY_REPLY, reply.remoteInputs.single().resultKey)
    }

    @Test
    fun `the buttons are broadcasts to the app's own receiver, never open to others`() {
        notifier.show(payload("task_due"))

        val pending = shown().actions.first().actionIntent
        val intent: Intent = shadowOf(pending).savedIntent
        assertEquals(PushActionReceiver::class.java.name, intent.component?.className)
        assertEquals(PushActionReceiver.actionFor(PushActionKind.DONE), intent.action)
        assertEquals("n-1", intent.getStringExtra(PushIntents.EXTRA_NOTIFICATION_ID))
    }

    @Test
    fun `a message with a link offers C'est noté, Y aller and Plus tard, and a tap on its body opens the interruption instead`() {
        val link = "${BuildConfig.DEEP_LINK_SCHEME}://finance/banks"
        notifier.show(payload("finance", link = link))

        assertEquals(listOf("C'est noté", "Y aller", "Plus tard"), buttons())
        val tap = shadowOf(shown().contentIntent).savedIntent
        assertNull(tap.data)
        assertEquals("n-1", tap.getStringExtra(PushIntents.EXTRA_NOTIFICATION_ID))

        val go = shadowOf(shown().actions[1].actionIntent).savedIntent
        assertEquals(Intent.ACTION_VIEW, go.action)
        assertEquals(link, go.dataString)
    }

    @Test
    fun `showing it again replaces it instead of stacking`() {
        notifier.show(payload("reminder"))
        notifier.show(payload("reminder"))

        assertEquals(1, shadowOf(manager).allNotifications.size)
    }

    @Test
    fun `a note says why a button did not go through, in place of the text`() {
        notifier.show(payload("task_due"), note = "Maggie est injoignable. Réessayez.")

        assertEquals("Maggie est injoignable. Réessayez.", shown().extras.getString("android.text"))
        assertEquals(2, shown().actions.size)
    }

    @Test
    fun `a settled request has no button left`() {
        notifier.show(payload("approval", approvalId = "ap-1"), note = "Cette demande n'est plus en attente.", withActions = false)

        assertNull(shown().actions)
    }

    @Test
    fun `closing removes it`() {
        notifier.show(payload("reminder"))
        notifier.close("n-1")

        assertNull(shown())
    }

    @Test
    fun `postponing removes it and brings it back later`() {
        notifier.show(payload("task_due"))
        notifier.postpone(payload("task_due"))

        assertNull(shown())
        assertEquals(1, shadowOf(app.getSystemService(android.app.AlarmManager::class.java)).scheduledAlarms.size)
    }

    private fun reminder(type: String, actions: String, taskId: String? = "t-1") = PushPayload.from(
        data = buildMap {
            put("notificationId", "n-1")
            put("type", type)
            put("title", "Le garage, tu t'y mets ?")
            put("reminderId", "r-1")
            taskId?.let { put("taskId", it) }
            put("actions", actions)
        },
    )!!

    @Test
    fun `a task reminder carries its buttons in the server's order (MAG-510)`() {
        notifier.show(reminder("task_nudge", "start,in10,tonight"))
        assertEquals(listOf("Je m'y mets", "Dans 10 min", "Ce soir"), buttons())

        notifier.show(reminder("task_due", "start,done,tomorrow"))
        assertEquals(listOf("Je m'y mets", "Fait", "Demain"), buttons())
        assertEquals(PushChannels.REMINDERS, shown().channelId)
    }

    @Test
    fun `Je m'y mets opens the app on the task's Focus and asks it to answer the reminder`() {
        notifier.show(reminder("task_nudge", "start,in10,tonight"))

        val start = shadowOf(shown().actions.first().actionIntent)
        assertTrue("an activity, never a broadcast", start.isActivity)
        val intent = start.savedIntent
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("${BuildConfig.DEEP_LINK_SCHEME}://task/t-1/focus", intent.dataString)
        assertEquals(PushActionKind.START to "r-1", PushIntents.answerOf(intent)?.let { (kind, payload) -> kind to payload.reminderId })
    }

    @Test
    fun `the other reminder buttons are broadcasts to the app's receiver`() {
        notifier.show(reminder("task_nudge", "start,in10,tonight"))

        val in10 = shadowOf(shown().actions[1].actionIntent)
        assertTrue(in10.isBroadcast)
        assertEquals(PushActionReceiver.actionFor(PushActionKind.IN_TEN), in10.savedIntent.action)
        assertEquals("r-1", in10.savedIntent.getStringExtra(PushIntents.EXTRA_REMINDER_ID))
    }

    @Test
    fun `Je m'y mets without a task only answers the reminder`() {
        notifier.show(reminder("task_nudge", "start,in10", taskId = null))

        val start = shadowOf(shown().actions.first().actionIntent)
        assertTrue(start.isBroadcast)
        assertEquals(PushActionReceiver.actionFor(PushActionKind.START), start.savedIntent.action)
    }

    @Test
    fun `a shortage alert carries Ajouter à la liste and Plus tard as broadcasts to the receiver (MAG-570)`() {
        notifier.show(
            PushPayload.from(
                mapOf(
                    "notificationId" to "n-1",
                    "type" to "grocery",
                    "title" to "Il manque : parmesan",
                    "shortageId" to "01HZX3K9P2M7Q4R8S5T6V0W1AB",
                    "actions" to "add",
                ),
            )!!,
        )

        assertEquals(listOf("Ajouter à la liste", "Plus tard"), buttons())
        val add = shadowOf(shown().actions.first().actionIntent)
        assertTrue(add.isBroadcast)
        assertEquals(PushActionReceiver.actionFor(PushActionKind.ADD_TO_LIST), add.savedIntent.action)
        assertEquals("01HZX3K9P2M7Q4R8S5T6V0W1AB", add.savedIntent.getStringExtra(PushIntents.EXTRA_SHORTAGE_ID))
    }

    // --- A cooking timer's end (MAG-582)

    private fun timerEnd() = PushPayload.from(
        data = mapOf(
            "notificationId" to "n-9",
            "type" to "cooking_timer",
            "title" to "Pâtes : c'est prêt",
            "body" to "minuteur de l'étape 3 · Pâtes aux légumes",
            "cookingTimerId" to "t-1",
        ),
    )!!

    @Test
    fun `a cooking timer's end is posted under cooking-timer-id on the reminders channel`() {
        shadowOf(manager).setNotificationsEnabled(true)

        assertTrue(notifier.show(timerEnd()))

        val notification = shadowOf(manager).getNotification("cooking-timer-t-1", PushNotifier.NOTIFICATION_ID)
        assertNotNull(notification)
        assertEquals(PushChannels.REMINDERS, notification.channelId)
        assertNull("not under its notification id", shadowOf(manager).getNotification("n-9", PushNotifier.NOTIFICATION_ID))
    }

    @Test
    fun `a cooking timer's end is taken down by its tag`() {
        shadowOf(manager).setNotificationsEnabled(true)
        notifier.show(timerEnd())

        notifier.close(timerEnd().systemTag)

        assertNull(shadowOf(manager).getNotification("cooking-timer-t-1", PushNotifier.NOTIFICATION_ID))
    }
}

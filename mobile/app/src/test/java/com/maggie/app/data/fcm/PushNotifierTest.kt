package com.maggie.app.data.fcm

import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import androidx.core.app.NotificationCompat
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

    // --- A message of Maggie, as a conversation (MAG-682)

    private fun chat(id: String, body: String, title: String = "Maggie") = PushPayload.from(
        data = mapOf("notificationId" to id, "type" to "proaction", "title" to title, "body" to body),
    )!!

    private fun conversation(tag: String = "n-1") =
        NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(
            shadowOf(manager).getNotification(tag, PushNotifier.NOTIFICATION_ID),
        )

    @Test
    fun `a message of Maggie is a conversation from Maggie, with her portrait, in the conversation group`() {
        notifier.show(chat("n-1", "Tu as dentiste demain à 9 h."))

        val style = conversation()!!
        val message = style.messages.single()
        assertEquals("Tu as dentiste demain à 9 h.", message.text.toString())
        assertEquals("Maggie", message.person?.name.toString())
        assertNotNull("her portrait", message.person?.icon)
        assertEquals(NotificationCompat.CATEGORY_MESSAGE, shown().category)
        assertEquals(PushNotifier.GROUP_CONVERSATION, shown().group)
    }

    @Test
    fun `a headline that is not just Maggie is read before the message`() {
        notifier.show(chat("n-1", "À 9 h.", title = "Dentiste demain"))

        assertEquals("Dentiste demain\nÀ 9 h.", conversation()!!.messages.single().text.toString())
    }

    @Test
    fun `Répondre is the reply semantic action and Marquer comme lu an invisible one that is not in the tray`() {
        notifier.show(chat("n-1", "Salut"))

        val reply = shown().actions.single()
        assertEquals(NotificationCompat.Action.SEMANTIC_ACTION_REPLY, NotificationCompat.getAction(shown(), 0)!!.semanticAction)
        assertEquals("Répondre", reply.title.toString())
        assertFalse(NotificationCompat.getAction(shown(), 0)!!.showsUserInterface)

        val markRead = NotificationCompat.getInvisibleActions(shown()).single()
        assertEquals("Marquer comme lu", markRead.title.toString())
        assertEquals(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ, markRead.semanticAction)
        assertFalse(markRead.showsUserInterface)
        val intent = shadowOf(markRead.actionIntent).savedIntent
        assertTrue(shadowOf(markRead.actionIntent).isBroadcast)
        assertEquals(PushActionReceiver::class.java.name, intent.component?.className)
        assertEquals(PushActionReceiver.actionFor(PushActionKind.MARK_AS_READ), intent.action)
        assertEquals("n-1", intent.getStringExtra(PushIntents.EXTRA_NOTIFICATION_ID))
    }

    @Test
    fun `the next message brings the earlier ones along and takes their place in the tray`() {
        notifier.show(chat("n-1", "Premier"))
        notifier.show(chat("n-2", "Deuxième"))

        assertNull("folded into the newest", shown())
        assertEquals(1, shadowOf(manager).allNotifications.size)
        assertEquals(listOf("Premier", "Deuxième"), conversation("n-2")!!.messages.map { it.text.toString() })
    }

    @Test
    fun `the conversation keeps the last messages only`() {
        (1..9).forEach { notifier.show(chat("n-$it", "Message $it")) }

        val texts = conversation("n-9")!!.messages.map { it.text.toString() }
        assertEquals(6, texts.size)
        assertEquals("Message 9", texts.last())
    }

    @Test
    fun `other notifications are no conversation and carry no invisible action`() {
        notifier.show(payload("reminder"))
        notifier.show(chat("n-3", "Salut"))

        assertNull(conversation("n-1"))
        assertTrue(NotificationCompat.getInvisibleActions(shown()).isEmpty())
        assertNotNull("the reminder is left alone", shown())
    }

    @Test
    fun `a failed reply says so in plain text and keeps Répondre`() {
        notifier.show(chat("n-1", "Salut"), note = "Maggie est injoignable. Réessayez.")

        assertNull(conversation())
        assertEquals(listOf("Répondre"), buttons())
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

    private fun departure(link: String? = "https://waze.com/ul?navigate=yes") = PushPayload.from(
        data = buildMap {
            put("notificationId", "n-5")
            put("type", "departure")
            put("title", "Pars maintenant pour Dentiste")
            link?.let { put("link", it) }
        },
    )!!

    @Test
    fun `a departure alert is a strong interruption, with its channel, its full screen and its two buttons (MAG-658)`() {
        shadowOf(manager).setNotificationsEnabled(true)

        assertTrue(notifier.show(departure()))

        val notification = shadowOf(manager).getNotification("n-5", PushNotifier.NOTIFICATION_ID)
        assertEquals(PushChannels.DEPARTURE, notification.channelId)
        assertEquals(listOf("Y aller", "Déjà en route"), notification.actions.map { it.title.toString() })
        val fullScreen = notification.fullScreenIntent
        assertNotNull("the screen « Pars maintenant » opens over the lock screen", fullScreen)
        val screen = shadowOf(fullScreen).savedIntent
        assertEquals(com.maggie.app.ui.screens.departure.DepartureActivity::class.java.name, screen.component?.className)
        assertNull(screen.action)
    }

    @Test
    fun `Y aller of a departure goes through the departure activity, which hands the link over and closes the notification`() {
        shadowOf(manager).setNotificationsEnabled(true)
        notifier.show(departure())

        val go = shadowOf(shadowOf(manager).getNotification("n-5", PushNotifier.NOTIFICATION_ID).actions[0].actionIntent).savedIntent

        assertEquals(com.maggie.app.ui.screens.departure.DepartureActivity::class.java.name, go.component?.className)
        assertEquals(PushIntents.ACTION_DEPARTURE_GO, go.action)
    }

    @Test
    fun `a departure alert with nothing left to press has no full screen`() {
        shadowOf(manager).setNotificationsEnabled(true)

        notifier.show(departure(), note = "Maggie est injoignable.", withActions = false)

        val notification = shadowOf(manager).getNotification("n-5", PushNotifier.NOTIFICATION_ID)
        assertNull(notification.fullScreenIntent)
        assertTrue(notification.actions.isNullOrEmpty())
    }
}

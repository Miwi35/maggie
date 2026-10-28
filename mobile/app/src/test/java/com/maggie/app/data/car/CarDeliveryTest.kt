package com.maggie.app.data.car

import android.app.Application
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.fcm.PushActionKind
import com.maggie.app.data.fcm.PushChannels
import com.maggie.app.data.fcm.PushDelivery
import com.maggie.app.data.fcm.PushNotifier
import com.maggie.app.data.fcm.PushPayload
import com.maggie.app.data.interruption.InterruptionCenter
import com.maggie.app.data.interruption.Interruptions
import com.maggie.app.data.model.PendingApproval
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** The car connected, the real notifier: what the phone shows and what the car can read (MAG-683). */
@RunWith(AndroidJUnit4::class)
class CarDeliveryTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val manager = app.getSystemService(NotificationManager::class.java)
    private val notifier = PushNotifier(app)
    private var inCar = true
    private var foreground = true
    private val scheduled = mutableListOf<Pair<String, Long>>()

    private val car = CarMessages(
        isConnected = { inCar },
        post = { payload, nudge -> notifier.show(payload, inCar = true, nudge = nudge) },
        isShowing = notifier::isShowing,
        scheduleNudge = { payload, delayMs -> scheduled += payload.notificationId to delayMs },
        cancelNudge = {},
    )
    private val center = InterruptionCenter(car = car)
    private val delivery = PushDelivery(center, { notifier }, car, isForeground = { foreground })

    @Before
    fun setUp() {
        PushChannels.create(app)
        shadowOf(manager).setNotificationsEnabled(true)
    }

    private fun push(id: String, type: String, vararg extra: Pair<String, String>) =
        PushPayload.from(mapOf("notificationId" to id, "type" to type, "title" to "Titre", "body" to "Texte") + extra)!!

    private fun shown(tag: String) = shadowOf(manager).getNotification(tag, PushNotifier.NOTIFICATION_ID)

    private fun buttons(tag: String) = shown(tag).actions.orEmpty().map { it.title.toString() }

    @Test
    fun `in the car with the app open an interruption is a conversation notification, and the phone shows nothing`() {
        delivery.deliver(push("n-1", "task_nudge", "reminderId" to "r-1", "actions" to "done,in10,tonight"))

        assertNull("no toaster, no overlay", center.current.value)
        val notification = shown("n-1")
        assertNotNull(NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification))
        assertEquals(NotificationCompat.Action.SEMANTIC_ACTION_REPLY, NotificationCompat.getAction(notification, 0)!!.semanticAction)
        assertEquals(listOf("Répondre", "Fait", "Dans 10 min"), buttons("n-1"))
        assertEquals(listOf("n-1" to 300_000L), scheduled)
    }

    @Test
    fun `what the feeds raise while the app is open is said in the car too`() {
        val approval = Interruptions.of(PendingApproval(id = "ap-1", toolName = "delete_event", summary = "Supprimer le dentiste", status = "pending"))!!

        center.offer(approval)

        assertNull(center.current.value)
        assertEquals(listOf("Répondre", "Autoriser", "Refuser"), buttons("ap-1"))
    }

    @Test
    fun `ordinary messages of Maggie are one conversation in the car, app open or closed, and wait for nothing`() {
        delivery.deliver(push("n-1", "proaction"))
        foreground = false
        delivery.deliver(push("n-2", "proaction"))

        assertNull(center.current.value)
        assertEquals(listOf("Répondre"), buttons("n-2"))
        assertEquals(2, NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(shown("n-2"))!!.messages.size)
        assertTrue(scheduled.isEmpty())
    }

    @Test
    fun `a message that waits for an answer keeps its own notification when others arrive`() {
        delivery.deliver(push("n-1", "task_nudge", "reminderId" to "r-1", "actions" to "done,in10"))
        delivery.deliver(push("n-2", "proaction"))

        assertEquals(listOf("Répondre", "Fait", "Dans 10 min"), buttons("n-1"))
        assertEquals(listOf("Répondre"), buttons("n-2"))
    }

    @Test
    fun `a departure alert in the car is a conversation, not a full screen`() {
        delivery.deliver(push("n-1", "departure", "link" to "https://waze.com/ul?x=1"))

        assertNull(shown("n-1").fullScreenIntent)
        assertNotNull(NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(shown("n-1")))
    }

    @Test
    fun `out of the car nothing changes`() {
        inCar = false

        delivery.deliver(push("n-1", "proaction", "interaction" to """{"kind":"ack"}"""))

        assertEquals("n-1", center.current.value?.notificationId)
        assertNull(shadowOf(manager).allNotifications.firstOrNull())
    }

    @Test
    fun `the alarm tells a message nobody reacted to once more, as a new message of the conversation`() {
        val reminder = push("n-1", "task_nudge", "reminderId" to "r-1", "actions" to "done,in10")
        delivery.deliver(reminder)

        car.nudge(reminder)

        val messages = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(shown("n-1"))!!.messages
        assertEquals("Je te relance. Titre\nTexte", messages.last().text.toString())
    }

    @Test
    fun `the car gone, what nobody answered comes back as the usual interruption on the phone`() {
        delivery.deliver(push("n-1", "task_nudge", "reminderId" to "r-1", "actions" to "done,in10"))
        delivery.deliver(push("n-2", "proaction"))
        inCar = false

        delivery.backOnPhone(car.left())

        assertEquals("n-1", center.current.value?.notificationId)
        assertNull("the car's copy is taken down", shadowOf(manager).getNotification("n-1", PushNotifier.NOTIFICATION_ID))
        assertNotNull("a message that waited for nothing stays where it is", shown("n-2"))
    }

    @Test
    fun `the car gone with the app closed, the waiting message is the usual notification`() {
        delivery.deliver(push("n-1", "task_nudge", "reminderId" to "r-1", "actions" to "done,in10"))
        foreground = false
        inCar = false

        delivery.backOnPhone(car.left())

        assertNull(center.current.value)
        assertEquals(listOf("Fait", "Dans 10 min"), buttons("n-1"))
        assertNull(NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(shown("n-1")))
    }

    @Test
    fun `a message answered in the car does not come back on the phone`() {
        delivery.deliver(push("n-1", "task_nudge", "reminderId" to "r-1", "actions" to "done,in10"))
        notifier.close("n-1")
        inCar = false

        delivery.backOnPhone(car.left())

        assertNull(center.current.value)
    }

    @Test
    fun `Répondre on a reminder is a button the car can press`() {
        delivery.deliver(push("n-1", "task_nudge", "reminderId" to "r-1", "actions" to "done"))

        val reply = shown("n-1").actions.first()
        assertEquals(PushActionKind.REPLY.label, reply.title.toString())
        assertEquals(1, reply.remoteInputs.size)
    }

    @Test
    fun `a message read elsewhere is taken down and no longer waits`() {
        delivery.deliver(push("n-1", "task_nudge", "reminderId" to "r-1", "actions" to "done,in10"))

        delivery.withdraw("n-1", "n-1")
        inCar = false

        assertTrue(car.left().isEmpty())
        assertNull(shadowOf(manager).getNotification("n-1", PushNotifier.NOTIFICATION_ID))
    }
}

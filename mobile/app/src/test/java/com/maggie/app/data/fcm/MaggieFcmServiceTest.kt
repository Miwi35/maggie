package com.maggie.app.data.fcm

import android.app.Application
import android.app.NotificationManager
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.firebase.messaging.RemoteMessage
import com.maggie.app.data.car.noCar
import com.maggie.app.data.interruption.InterruptionCenter
import com.maggie.app.data.location.LocationScheduler
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf

/** Every push is data alone (MAG-681): the service builds the notification itself, with the app killed or in front. */
@RunWith(AndroidJUnit4::class)
class MaggieFcmServiceTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val manager = app.getSystemService(NotificationManager::class.java)
    private val car = noCar()
    private val center = InterruptionCenter(car = car)
    private var foreground = false

    @Before
    fun setUp() {
        stopKoin()
        startKoin {
            modules(
                module {
                    single { mockk<PushTokenRegistrar>(relaxed = true) }
                    single { mockk<LocationScheduler>(relaxed = true) }
                    single { car }
                    single { PushDelivery(center, { PushNotifier(app) }, car, isForeground = { foreground }) }
                },
            )
        }
        PushChannels.create(app)
        shadowOf(manager).setNotificationsEnabled(true)
    }

    @After
    fun tearDown() = stopKoin()

    private fun receive(vararg data: Pair<String, String>) {
        val bundle = Bundle().apply { data.forEach { (key, value) -> putString(key, value) } }
        Robolectric.setupService(MaggieFcmService::class.java).onMessageReceived(RemoteMessage(bundle))
    }

    private fun shown(tag: String) = shadowOf(manager).getNotification(tag, PushNotifier.NOTIFICATION_ID)

    @Test
    fun `with the app killed a data-only push becomes a notification built by the app, in its channel`() {
        receive(
            "notificationId" to "n-1",
            "type" to "approval",
            "title" to "Je peux payer ?",
            "body" to "Facture de 40 €",
            "channel" to PushChannels.APPROVALS,
        )

        val notification = shown("n-1")
        assertNotNull(notification)
        assertEquals(PushChannels.APPROVALS, notification.channelId)
        assertEquals("Je peux payer ?", notification.extras.getString("android.title"))
        assertEquals("Facture de 40 €", notification.extras.getString("android.text"))
        assertNull(center.current.value)
    }

    @Test
    fun `a task reminder keeps its buttons in the order the server lists them`() {
        receive(
            "notificationId" to "n-2",
            "type" to "task_nudge",
            "title" to "Suivante : « Appeler le garagiste ».",
            "channel" to PushChannels.REMINDERS,
            "reminderId" to "r-1",
            "taskId" to "t-1",
            "actions" to "start,in10,tonight",
        )

        val notification = shown("n-2")
        assertEquals(PushChannels.REMINDERS, notification.channelId)
        assertEquals(listOf("Je m'y mets", "Dans 10 min", "Ce soir"), notification.actions.map { it.title.toString() })
    }

    @Test
    fun `a push without a channel lands in the channel of its type`() {
        receive("notificationId" to "n-3", "type" to "finance", "title" to "Banque à reconnecter")

        assertEquals(PushChannels.FINANCE, shown("n-3").channelId)
    }

    @Test
    fun `a cooking timer is filed under its own tag`() {
        receive(
            "notificationId" to "n-4",
            "type" to "cooking_timer",
            "title" to "Pâtes : c'est prêt",
            "cookingTimerId" to "timer-9",
        )

        assertNotNull(shown("cooking-timer-timer-9"))
    }

    @Test
    fun `with the app in front the push is an interruption and no notification`() {
        foreground = true

        receive("notificationId" to "n-5", "type" to "reminder", "title" to "Dentiste")

        assertNull(shown("n-5"))
        assertEquals("n-5", center.current.value?.notificationId)
    }

    @Test
    fun `a withdrawal takes the notification down even when the app is closed`() {
        receive("notificationId" to "n-6", "type" to "reminder", "title" to "Dentiste")
        assertNotNull(shown("n-6"))

        receive("type" to "withdraw", "notificationId" to "n-6", "tag" to "n-6")

        assertNull(shown("n-6"))
    }

    private val messageData = arrayOf(
        "notificationId" to "m-1",
        "type" to "message",
        "channel" to PushChannels.CHAT,
        "messageId" to "01JABCDEFGHJKMNPQRSTVWXYZ0",
        "link" to "maggie://chat?message=01JABCDEFGHJKMNPQRSTVWXYZ0",
        "actionLabel" to "Ouvrir la conversation",
        "title" to "Maggie",
        "body" to "Tu as dentiste demain à 9 h.",
    )

    @Test
    fun `a message of Maggie is the conversation notification with Répondre, and its tap opens the message (MAG-687)`() {
        receive(*messageData)

        val notification = shown("m-1")
        assertEquals(PushChannels.CHAT, notification.channelId)
        assertEquals(listOf("Répondre"), notification.actions.map { it.title.toString() })
        val style = androidx.core.app.NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)!!
        assertEquals("Tu as dentiste demain à 9 h.", style.messages.single().text.toString())
        val opened = shadowOf(notification.contentIntent).savedIntent
        assertEquals("maggie://chat?message=01JABCDEFGHJKMNPQRSTVWXYZ0", opened.data.toString())
    }

    @Test
    fun `during the quiet hours the message is posted on the quiet channel, without sound or vibration (MAG-687)`() {
        receive(*messageData, "silent" to "1")

        val notification = shown("m-1")
        assertEquals(PushChannels.CHAT_QUIET, notification.channelId)
        val channel = manager.getNotificationChannel(PushChannels.CHAT_QUIET)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        assertNull(channel.sound)
        assertEquals(false, channel.shouldVibrate())
        assertEquals(listOf("Répondre"), notification.actions.map { it.title.toString() })
    }

    @Test
    fun `with the chat on screen on this device a message raises nothing, not even an interruption (MAG-687)`() {
        foreground = true
        center.hold("message")

        receive(*messageData)

        assertNull(shown("m-1"))
        assertNull(center.current.value)
    }

    @Test
    fun `the chat open but the app in the background still gets the notification`() {
        foreground = false
        center.hold("message")

        receive(*messageData)

        assertNotNull(shown("m-1"))
    }

    @Test
    fun `a message pushed twice is one notification, whichever way it came (MAG-687)`() {
        receive(*messageData)
        receive(*messageData)

        assertEquals(1, shadowOf(manager).allNotifications.size)
    }
}

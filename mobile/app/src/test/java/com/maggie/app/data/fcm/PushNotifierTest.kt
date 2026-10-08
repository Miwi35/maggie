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
    fun `a reminder carries OK`() {
        notifier.show(payload("reminder"))
        assertEquals(listOf("OK"), buttons())
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
    fun `a message with a link opens it on a tap and offers Y aller`() {
        val link = "${BuildConfig.DEEP_LINK_SCHEME}://finance/banks"
        notifier.show(payload("finance", link = link))

        assertEquals(listOf("Y aller", "Plus tard"), buttons())
        val tap = shadowOf(shown().contentIntent).savedIntent
        assertEquals(Intent.ACTION_VIEW, tap.action)
        assertEquals(link, tap.dataString)
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
}

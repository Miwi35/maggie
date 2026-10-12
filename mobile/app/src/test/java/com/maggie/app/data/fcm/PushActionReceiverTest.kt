package com.maggie.app.data.fcm

import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import android.os.Bundle
import androidx.core.app.RemoteInput
import com.maggie.app.data.interruption.InterruptionCenter
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.coEvery
import io.mockk.coVerify
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
import org.robolectric.Shadows.shadowOf

/** The buttons as Android fires them: the broadcast reaches the receiver, which answers and updates the notification. */
@RunWith(AndroidJUnit4::class)
class PushActionReceiverTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val manager = app.getSystemService(NotificationManager::class.java)
    private val handler = mockk<PushActionHandler>()
    private val center = InterruptionCenter()
    private var foreground = false

    @Before
    fun setUp() {
        stopKoin()
        startKoin {
            modules(
                module {
                    single { handler }
                    single { PushDelivery(center, { PushNotifier(app) }, isForeground = { foreground }) }
                },
            )
        }
        PushChannels.create(app)
        shadowOf(manager).setNotificationsEnabled(true)
    }

    @After
    fun tearDown() = stopKoin()

    private fun payload(type: String) = PushPayload.from(
        data = mapOf("notificationId" to "n-1", "type" to type, "title" to "Titre", "body" to "Texte"),
    )!!

    private fun shown() = shadowOf(manager).getNotification("n-1", PushNotifier.NOTIFICATION_ID)

    private fun press(label: String, reply: String? = null) {
        val action = shown().actions.first { it.title.toString() == label }
        val intent = Intent(shadowOf(action.actionIntent).savedIntent)
        if (reply != null) {
            RemoteInput.addResultsToIntent(
                arrayOf(RemoteInput.Builder(PushActionReceiver.KEY_REPLY).build()),
                intent,
                Bundle().apply { putCharSequence(PushActionReceiver.KEY_REPLY, reply) },
            )
        }
        app.sendBroadcast(intent)
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 3_000
        while (!condition() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
            shadowOf(android.os.Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun `pressing OK hands the button to the handler and removes the notification`() {
        coEvery { handler.handle(PushActionKind.OK, any(), any(), any()) } returns PushOutcome.Closed
        PushNotifier(app).show(payload("reminder"))

        press("OK")
        await { shown() == null }

        assertNull(shown())
        coVerify { handler.handle(PushActionKind.OK, match { it.notificationId == "n-1" }, null, any()) }
    }

    @Test
    fun `the typed reply travels with the Répondre button`() {
        coEvery { handler.handle(PushActionKind.REPLY, any(), "Oui, merci", any()) } returns PushOutcome.Closed
        PushNotifier(app).show(payload("proaction"))

        press("Répondre", reply = "Oui, merci")
        await { shown() == null }

        assertNull(shown())
        coVerify { handler.handle(PushActionKind.REPLY, any(), "Oui, merci", any()) }
    }

    @Test
    fun `a failed answer keeps the notification, with the reason and its buttons`() {
        coEvery { handler.handle(PushActionKind.OK, any(), any(), any()) } returns PushOutcome.Retry("Maggie est injoignable. Réessayez.")
        PushNotifier(app).show(payload("reminder"))

        press("OK")
        await { shown()?.extras?.getString("android.text") == "Maggie est injoignable. Réessayez." }

        assertEquals("Maggie est injoignable. Réessayez.", shown().extras.getString("android.text"))
        assertEquals(listOf("OK"), shown().actions.map { it.title.toString() })
    }

    @Test
    fun `a request already answered elsewhere stays visible without buttons`() {
        coEvery { handler.handle(PushActionKind.OK, any(), any(), any()) } returns PushOutcome.Settled("Cette demande n'est plus en attente.")
        PushNotifier(app).show(payload("reminder"))

        press("OK")
        await { shown()?.actions.isNullOrEmpty() }

        assertNotNull(shown())
        assertEquals("Cette demande n'est plus en attente.", shown().extras.getString("android.text"))
        assertEquals(0, shown().actions?.size ?: 0)
    }

    @Test
    fun `Plus tard removes the notification and sets the alarm that brings it back`() {
        coEvery { handler.handle(PushActionKind.LATER, any(), any(), any()) } returns PushOutcome.Postponed
        PushNotifier(app).show(payload("task_due"))

        val alarms = shadowOf(app.getSystemService(android.app.AlarmManager::class.java))

        press("Plus tard")
        await { shown() == null && alarms.scheduledAlarms.isNotEmpty() }

        assertNull(shown())
        assertEquals(1, alarms.scheduledAlarms.size)
    }

    private fun comeBack() {
        val intent = Intent(app, PushActionReceiver::class.java).setAction(PushActionReceiver.ACTION_RESHOW)
        PushIntents.put(intent, payload("task_due"))
        app.sendBroadcast(intent)
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    @Test
    fun `a postponed notification comes back as a notification when the app is closed`() {
        comeBack()

        assertNotNull(shown())
        assertNull(center.current.value)
    }

    @Test
    fun `a postponed notification comes back as an interruption when the app is open`() {
        foreground = true

        comeBack()

        assertNull(shown())
        assertEquals("n-1", center.current.value?.notificationId)
    }
}

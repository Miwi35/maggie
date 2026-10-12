package com.maggie.app.data.fcm

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** The tap on a push opens the app on the interruption; « Y aller » follows `data.link`, and only a link of the app's own scheme. */
@RunWith(AndroidJUnit4::class)
class PushIntentsTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val scheme = BuildConfig.DEEP_LINK_SCHEME

    @Test
    fun `only the app's own scheme is a link, whoever sent the message`() {
        assertNotNull(PushIntents.linkOf("$scheme://event/e-1"))
        assertNull(PushIntents.linkOf("https://evil.example/e-1"))
        assertNull(PushIntents.linkOf("intent://x#Intent;end"))
        assertNull(PushIntents.linkOf(null))
    }

    @Test
    fun `a tap on the notification asks the app to say it again, without leaving the screen it is on`() {
        val payload = PushPayload.from(mapOf("notificationId" to "n-1", "title" to "Rappel", "link" to "$scheme://finance/banks"))!!

        val tap = PushIntents.open(context, payload)

        assertNull(tap.data)
        assertEquals(payload, PushIntents.interruptionOf(tap))
    }

    @Test
    fun `only Y aller goes straight to the link`() {
        val payload = PushPayload.from(mapOf("notificationId" to "n-1", "link" to "$scheme://finance/banks"))!!

        val go = PushIntents.open(context, payload, toLink = true)

        assertEquals(Intent.ACTION_VIEW, go.action)
        assertEquals("$scheme://finance/banks", go.dataString)
        assertNull("a link opened on purpose is not an interruption", PushIntents.interruptionOf(go))
    }

    @Test
    fun `a link of another scheme is never followed`() {
        val payload = PushPayload.from(mapOf("notificationId" to "n-1", "link" to "https://evil.example"))!!

        assertNull(PushIntents.open(context, payload, toLink = true).data)
    }

    @Test
    fun `an intent with no push in it, or a link opened from outside, says nothing`() {
        assertNull(PushIntents.interruptionOf(Intent(Intent.ACTION_MAIN)))
        assertNull(PushIntents.interruptionOf(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("$scheme://chat"))))
    }

    @Test
    fun `a push without a link opens the app on its home`() {
        val payload = PushPayload.from(mapOf("notificationId" to "n-1"))!!

        val intent = PushIntents.open(context, payload)

        assertNull(intent.data)
        assertEquals("n-1", intent.getStringExtra(PushIntents.EXTRA_NOTIFICATION_ID))
    }

    @Test
    fun `a push survives the trip through an intent`() {
        val payload = PushPayload.from(
            mapOf(
                "notificationId" to "n-1",
                "type" to "approval",
                "title" to "Valider",
                "body" to "Supprimer l'événement ?",
                "link" to "$scheme://event/e-1",
                "approvalId" to "ap-1",
            ),
        )!!

        val back = PushIntents.payloadOf(Intent().also { PushIntents.put(it, payload) })

        assertEquals(payload, back)
    }
}

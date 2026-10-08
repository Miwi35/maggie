package com.maggie.app.data.fcm

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

/** The tap on a push opens the link in `data.link` — and only a link of the app's own scheme. */
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
    fun `the intent Android's own notification fires gets the link as its data`() {
        val fired = Intent(Intent.ACTION_MAIN).putExtra(PushIntents.EXTRA_LINK, "$scheme://finance/banks")

        val opened = PushIntents.withLink(fired)

        assertEquals(Intent.ACTION_VIEW, opened.action)
        assertEquals("$scheme://finance/banks", opened.dataString)
    }

    @Test
    fun `an intent that already has a link or carries a foreign one is left alone`() {
        val withData = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("$scheme://chat"))
        assertSame(withData, PushIntents.withLink(withData))

        val foreign = Intent(Intent.ACTION_MAIN).putExtra(PushIntents.EXTRA_LINK, "https://evil.example")
        assertSame(foreign, PushIntents.withLink(foreign))
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

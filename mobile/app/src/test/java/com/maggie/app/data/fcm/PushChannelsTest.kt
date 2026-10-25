package com.maggie.app.data.fcm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PushChannelsTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(NotificationManager::class.java)

    private val chime: Uri = Uri.parse("android.resource://${context.packageName}/${R.raw.maggie_chime}")

    @Test
    fun `every channel rings the Maggie chime`() {
        PushChannels.create(context)

        PushChannels.ALL.forEach { id ->
            assertEquals("sound of $id", chime, manager.getNotificationChannel(id).sound)
        }
    }

    @Test
    fun `the default-sound channels of the previous generation are deleted at start`() {
        PushChannels.LEGACY.forEach { id ->
            manager.createNotificationChannel(NotificationChannel(id, id, NotificationManager.IMPORTANCE_HIGH))
        }

        PushChannels.create(context)

        PushChannels.LEGACY.forEach { id -> assertNull("$id still listed", manager.getNotificationChannel(id)) }
        PushChannels.ALL.forEach { id -> assertNotNull(manager.getNotificationChannel(id)) }
    }

    @Test
    fun `the new ids never reuse a deleted one`() {
        assertEquals(emptySet<String>(), PushChannels.ALL.intersect(PushChannels.LEGACY.toSet()))
    }

    @Test
    fun `a channel muted in the system settings is left as the owner set it`() {
        PushChannels.create(context)
        manager.getNotificationChannel(PushChannels.CHAT).apply {
            importance = NotificationManager.IMPORTANCE_NONE
        }.let(manager::createNotificationChannel)

        PushChannels.create(context)

        assertEquals(NotificationManager.IMPORTANCE_NONE, manager.getNotificationChannel(PushChannels.CHAT).importance)
    }

    @Test
    fun `a notification type lands on its channel`() {
        assertEquals(PushChannels.REMINDERS, PushChannels.forType("reminder"))
        assertEquals(PushChannels.REMINDERS, PushChannels.forType("task_due"))
        assertEquals(PushChannels.REMINDERS, PushChannels.forType("cooking_timer"))
        assertEquals(PushChannels.APPROVALS, PushChannels.forType("approval"))
        assertEquals(PushChannels.FINANCE, PushChannels.forType("finance"))
        assertEquals(PushChannels.FINANCE, PushChannels.forType("consent_expiring"))
        assertEquals(PushChannels.CHAT, PushChannels.forType("proaction"))
        assertEquals(PushChannels.CHAT, PushChannels.forType(null))
    }

    @Test
    fun `a routine reminder lands on the reminders channel (MAG-512)`() {
        assertEquals(PushChannels.REMINDERS, PushChannels.forType("routine"))
    }
}

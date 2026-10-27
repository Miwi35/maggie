package com.maggie.app.data.fcm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentResolver
import android.content.Context
import android.media.AudioAttributes
import android.net.Uri
import com.maggie.app.R

/**
 * The Android notification channels of the push.
 *
 * Their ids are a contract with the server: `PushMessageFactory` names one in every message
 * it sends, and a message sent to a channel the phone never created is shown without sound
 * or importance. `api/contract/push-channels.json` lists the server's, and
 * `PushChannelsContractTest` compares this list to it. No channel per notification type.
 *
 * Every channel rings Maggie's chime (`res/raw/maggie_chime.ogg`, the admin's). Android freezes a
 * channel's sound when it is created, and recreating a deleted id brings the old settings back:
 * a new sound therefore needs new ids (`_v2`), and [LEGACY] ones are deleted at each start.
 * A channel the owner muted in the system settings stays muted: an existing channel is never touched.
 */
object PushChannels {
    const val CHAT = "chat_v2"
    const val REMINDERS = "reminders_v2"
    const val APPROVALS = "approvals_v2"
    const val FINANCE = "finance_v2"

    /** Time to leave (MAG-655): strong importance, for the hard interruption of the departure alert. */
    const val DEPARTURE = "departure"

    /** The channels of the default-sound generation, removed from the phone's settings. */
    val LEGACY: List<String> = listOf("chat", "reminders", "approvals", "finance")

    val ALL: List<String> = listOf(CHAT, REMINDERS, APPROVALS, FINANCE, DEPARTURE)

    /** The channel the server picks for a notification type (`PushMessageFactory::channel`). */
    fun forType(type: String?): String = when (type) {
        "reminder", "task_due", "task_nudge", "routine", "cooking_timer" -> REMINDERS
        "approval" -> APPROVALS
        "departure" -> DEPARTURE
        "consent_expiring", "finance" -> FINANCE
        else -> CHAT
    }

    fun create(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        LEGACY.forEach(manager::deleteNotificationChannel)
        val chime = Uri.parse("${ContentResolver.SCHEME_ANDROID_RESOURCE}://${context.packageName}/${R.raw.maggie_chime}")
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        listOf(
            NotificationChannel(CHAT, "Messages", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Messages de Maggie"
            },
            NotificationChannel(REMINDERS, "Rappels", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Rappels d'événements et de tâches"
            },
            NotificationChannel(APPROVALS, "Validations", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Actions que Maggie attend de vous pour continuer"
            },
            NotificationChannel(FINANCE, "Finances", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Alertes sur vos comptes et vos banques"
            },
            NotificationChannel(DEPARTURE, "Départs", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "L'heure de partir pour un événement"
            },
        ).forEach { channel ->
            channel.setSound(chime, attributes)
            manager.createNotificationChannel(channel)
        }
    }
}

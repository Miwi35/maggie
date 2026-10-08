package com.maggie.app.data.fcm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/**
 * The Android notification channels of the push.
 *
 * Their ids are a contract with the server: `PushMessageFactory` names one in every message
 * it sends, and a message sent to a channel the phone never created is shown without sound
 * or importance. `api/contract/push-channels.json` lists the server's, and
 * `PushChannelsContractTest` compares this list to it. No channel per notification type.
 */
object PushChannels {
    const val CHAT = "chat"
    const val REMINDERS = "reminders"
    const val APPROVALS = "approvals"
    const val FINANCE = "finance"

    val ALL: List<String> = listOf(CHAT, REMINDERS, APPROVALS, FINANCE)

    /** The channel the server picks for a notification type (`PushMessageFactory::channel`). */
    fun forType(type: String?): String = when (type) {
        "reminder", "task_due" -> REMINDERS
        "approval" -> APPROVALS
        "consent_expiring", "finance" -> FINANCE
        else -> CHAT
    }

    fun create(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
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
        ).forEach(manager::createNotificationChannel)
    }
}

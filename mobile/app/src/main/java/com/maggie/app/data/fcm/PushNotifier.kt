package com.maggie.app.data.fcm

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import com.maggie.app.MainActivity
import com.maggie.app.R
import com.maggie.app.ui.navigation.DeepLinks

/**
 * Raises, updates and closes the system notification of a push, with its buttons.
 *
 * Android shows a push by itself while the app is closed (the `notification` block) and
 * hands the message to the app in the foreground. This builds the one the app raises
 * itself, under the same tag the server gives the system's, so the same message never
 * shows twice.
 */
class PushNotifier(private val context: Context) {

    /** False when Android would drop it: the permission was refused, or notifications are off. */
    fun canNotify(): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

    /**
     * @param note replaces the text, to say why a button did not go through.
     * @param withActions false once there is nothing left to press; the notification then dismisses itself.
     */
    fun show(payload: PushPayload, note: String? = null, withActions: Boolean = true): Boolean {
        if (!canNotify()) return false

        val builder = NotificationCompat.Builder(context, payload.channel)
            .setSmallIcon(R.drawable.maggie_logo)
            .setContentTitle(payload.title)
            .setContentText(note ?: payload.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(note ?: payload.text))
            .setAutoCancel(true)
            .setContentIntent(openIntent(payload))

        if (withActions) {
            payload.actions().forEach { builder.addAction(action(it, payload)) }
        } else {
            builder.setTimeoutAfter(SETTLED_TIMEOUT_MS)
        }

        context.getSystemService(NotificationManager::class.java)
            .notify(payload.notificationId, NOTIFICATION_ID, builder.build())
        return true
    }

    fun close(notificationId: String) {
        context.getSystemService(NotificationManager::class.java).cancel(notificationId, NOTIFICATION_ID)
    }

    /** « Plus tard »: the notification comes back, as it was, after [delayMs]. */
    fun postpone(payload: PushPayload, delayMs: Long = POSTPONE_MS) {
        close(payload.notificationId)
        val intent = broadcast(PushActionReceiver.ACTION_RESHOW, payload)
        val pending = PendingIntent.getBroadcast(
            context,
            requestCode(payload, PushActionReceiver.ACTION_RESHOW),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // Inexact on purpose: exact alarms need a permission the app has no reason to ask for.
        context.getSystemService(AlarmManager::class.java)
            .setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + delayMs, pending)
    }

    private fun action(kind: PushActionKind, payload: PushPayload): NotificationCompat.Action {
        if (kind == PushActionKind.GO) {
            return NotificationCompat.Action.Builder(0, kind.label, openIntent(payload)).build()
        }

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (kind == PushActionKind.REPLY) mutableFlag() else PendingIntent.FLAG_IMMUTABLE
        val pending = PendingIntent.getBroadcast(
            context,
            requestCode(payload, kind.name),
            broadcast(PushActionReceiver.actionFor(kind), payload),
            flags,
        )
        val builder = NotificationCompat.Action.Builder(0, kind.label, pending)
        if (kind == PushActionKind.REPLY) {
            builder
                .addRemoteInput(RemoteInput.Builder(PushActionReceiver.KEY_REPLY).setLabel(kind.label).build())
                .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
                .setShowsUserInterface(false)
        }
        return builder.build()
    }

    // The system fills the reply into the intent it fires, so Android 12+ needs it mutable.
    private fun mutableFlag(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0

    private fun broadcast(action: String, payload: PushPayload): Intent =
        Intent(action).setComponent(ComponentName(context, PushActionReceiver::class.java)).also { PushIntents.put(it, payload) }

    private fun openIntent(payload: PushPayload): PendingIntent = PendingIntent.getActivity(
        context,
        requestCode(payload, "open"),
        PushIntents.open(context, payload),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun requestCode(payload: PushPayload, what: String): Int = (payload.notificationId + what).hashCode()

    companion object {
        /** FCM raises its own under `(tag = notificationId, id = 0)`; sharing it is what dedupes. */
        const val NOTIFICATION_ID = 0
        const val POSTPONE_MS = 10 * 60 * 1000L
        private const val SETTLED_TIMEOUT_MS = 8_000L
    }
}

/** The extras that carry a push through an intent, and the link that opens it. */
object PushIntents {
    // The keys of FCM's own `data` block, so an intent from the system's notification reads the same.
    const val EXTRA_NOTIFICATION_ID = "notificationId"
    const val EXTRA_TYPE = "type"
    const val EXTRA_TITLE = "title"
    const val EXTRA_TEXT = "body"
    const val EXTRA_LINK = "link"
    const val EXTRA_APPROVAL_ID = "approvalId"
    const val EXTRA_CHANNEL = "channel"
    const val EXTRA_INTERACTION = "interaction"

    fun put(intent: Intent, payload: PushPayload) {
        intent.putExtra(EXTRA_NOTIFICATION_ID, payload.notificationId)
        intent.putExtra(EXTRA_TYPE, payload.type)
        intent.putExtra(EXTRA_TITLE, payload.title)
        intent.putExtra(EXTRA_TEXT, payload.text)
        intent.putExtra(EXTRA_LINK, payload.link)
        intent.putExtra(EXTRA_APPROVAL_ID, payload.approvalId)
        intent.putExtra(EXTRA_CHANNEL, payload.channel)
        payload.interaction?.let { intent.putExtra(EXTRA_INTERACTION, kotlinx.serialization.json.Json.encodeToString(PushInteraction.serializer(), it)) }
    }

    fun payloadOf(intent: Intent): PushPayload? = PushPayload.from(
        data = listOf(EXTRA_NOTIFICATION_ID, EXTRA_TYPE, EXTRA_TITLE, EXTRA_TEXT, EXTRA_LINK, EXTRA_APPROVAL_ID, EXTRA_INTERACTION)
            .mapNotNull { key -> intent.getStringExtra(key)?.let { key to it } }
            .toMap(),
        notificationChannel = intent.getStringExtra(EXTRA_CHANNEL),
    )

    /** The app link a push points at: only the app's own scheme, whoever sent the message. */
    fun linkOf(raw: String?): Uri? = raw?.let(Uri::parse)?.takeIf { it.scheme == DeepLinks.SCHEME }

    /** Opens the app on the link, or on its home when the push has none. */
    fun open(context: Context, payload: PushPayload): Intent {
        val link = linkOf(payload.link)
        return Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (link != null) {
                action = Intent.ACTION_VIEW
                data = link
            }
            putExtra(EXTRA_NOTIFICATION_ID, payload.notificationId)
        }
    }

    /**
     * The intent the system's own notification fires carries the message's `data` as extras and
     * no data URI, so the navigation graph cannot see the link. This gives it one.
     */
    fun withLink(intent: Intent): Intent {
        if (intent.data != null) return intent
        val link = linkOf(intent.getStringExtra(EXTRA_LINK)) ?: return intent
        return Intent(intent).apply {
            action = Intent.ACTION_VIEW
            data = link
        }
    }

    /** Opening the app from a notification is answering it: the system's own stays in the tray otherwise. */
    fun closeNotificationOf(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_NOTIFICATION_ID) ?: return
        context.getSystemService(NotificationManager::class.java).cancel(id, PushNotifier.NOTIFICATION_ID)
    }
}

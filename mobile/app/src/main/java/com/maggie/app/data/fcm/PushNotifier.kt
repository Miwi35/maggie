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
            .setContentIntent(openIntent(payload, toLink = false))

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
            return NotificationCompat.Action.Builder(0, kind.label, openIntent(payload, toLink = true)).build()
        }
        // « Je m'y mets » opens the Focus, so it is an activity intent too; the app answers the reminder once open.
        if (kind == PushActionKind.START) {
            PushIntents.startFocus(context, payload)?.let { intent ->
                val pending = PendingIntent.getActivity(
                    context,
                    requestCode(payload, kind.name),
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                return NotificationCompat.Action.Builder(0, kind.label, pending).build()
            }
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

    private fun openIntent(payload: PushPayload, toLink: Boolean): PendingIntent = PendingIntent.getActivity(
        context,
        requestCode(payload, if (toLink) "go" else "open"),
        PushIntents.open(context, payload, toLink),
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
    const val EXTRA_REMINDER_ID = "reminderId"
    const val EXTRA_TASK_ID = "taskId"
    const val EXTRA_ACTIONS = "actions"

    /** The reminder's button the app answers once it is open (« Je m'y mets »), by its key. */
    const val EXTRA_ANSWER = "com.maggie.app.push.ANSWER"

    fun put(intent: Intent, payload: PushPayload) {
        intent.putExtra(EXTRA_NOTIFICATION_ID, payload.notificationId)
        intent.putExtra(EXTRA_TYPE, payload.type)
        intent.putExtra(EXTRA_TITLE, payload.title)
        intent.putExtra(EXTRA_TEXT, payload.text)
        intent.putExtra(EXTRA_LINK, payload.link)
        intent.putExtra(EXTRA_APPROVAL_ID, payload.approvalId)
        intent.putExtra(EXTRA_CHANNEL, payload.channel)
        payload.interaction?.let { intent.putExtra(EXTRA_INTERACTION, kotlinx.serialization.json.Json.encodeToString(PushInteraction.serializer(), it)) }
        intent.putExtra(EXTRA_REMINDER_ID, payload.reminderId)
        intent.putExtra(EXTRA_TASK_ID, payload.taskId)
        if (payload.reminderActions.isNotEmpty()) intent.putExtra(EXTRA_ACTIONS, payload.reminderActions.joinToString(","))
    }

    fun payloadOf(intent: Intent): PushPayload? = PushPayload.from(
        data = listOf(
            EXTRA_NOTIFICATION_ID, EXTRA_TYPE, EXTRA_TITLE, EXTRA_TEXT, EXTRA_LINK, EXTRA_APPROVAL_ID, EXTRA_INTERACTION,
            EXTRA_REMINDER_ID, EXTRA_TASK_ID, EXTRA_ACTIONS,
        )
            .mapNotNull { key -> intent.getStringExtra(key)?.let { key to it } }
            .toMap(),
        notificationChannel = intent.getStringExtra(EXTRA_CHANNEL),
    )

    /** The app link a push points at: only the app's own scheme, whoever sent the message. */
    fun linkOf(raw: String?): Uri? = raw?.let(Uri::parse)?.takeIf { it.scheme == DeepLinks.SCHEME }

    /**
     * The tap on a notification opens the app on what Maggie said: the interruption, with its
     * action. Only « Y aller » ([toLink]) goes straight to the link, the app's home when the push has none.
     */
    fun open(context: Context, payload: PushPayload, toLink: Boolean = false): Intent {
        val link = if (toLink) linkOf(payload.link) else null
        return Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (link != null) {
                action = Intent.ACTION_VIEW
                data = link
            }
            put(this, payload)
        }
    }

    /**
     * « Je m'y mets » (MAG-510): the app opened on the Focus of the reminder's task, told to answer
     * the reminder. Null when the reminder names no task: the button is then a plain answer.
     */
    fun startFocus(context: Context, payload: PushPayload): Intent? {
        val link = linkOf(payload.focusLink()) ?: return null
        return Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            action = Intent.ACTION_VIEW
            data = link
            put(this, payload)
            putExtra(EXTRA_ANSWER, PushActionKind.START.reminderKey)
        }
    }

    /** The reminder's button an intent asks the app to answer, with its push; null for any other intent. */
    fun answerOf(intent: Intent): Pair<PushActionKind, PushPayload>? {
        // « Je m'y mets » only, the one answer the app puts there: the activity is exported, and
        // another app's intent must not tick a task or move it to tomorrow.
        val kind = intent.getStringExtra(EXTRA_ANSWER)?.let(PushActionKind::ofReminderKey)
            ?.takeIf { it == PushActionKind.START } ?: return null
        val payload = payloadOf(intent)?.takeIf { it.reminderId != null } ?: return null
        return kind to payload
    }

    /**
     * What a tap on a notification's body asks the app to say again, null for any other way in.
     * The intent the system's own notification fires carries the message's `data` as extras and
     * no data URI, like the one [open] builds; a link opened on purpose has a data URI.
     */
    fun interruptionOf(intent: Intent): PushPayload? = if (intent.data == null) payloadOf(intent) else null

    /** Opening the app from a notification is answering it: the system's own stays in the tray otherwise. */
    fun closeNotificationOf(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_NOTIFICATION_ID) ?: return
        context.getSystemService(NotificationManager::class.java).cancel(id, PushNotifier.NOTIFICATION_ID)
    }
}

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
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.graphics.drawable.IconCompat
import com.maggie.app.MainActivity
import com.maggie.app.R
import com.maggie.app.ui.navigation.DeepLinks
import com.maggie.app.ui.screens.departure.DepartureActivity
import com.maggie.app.util.stripDeliveryTags

/**
 * Raises, updates and closes the system notification of a push, with its buttons.
 *
 * Every push is data alone (MAG-681), so the app builds the notification itself, closed or
 * not: Android Auto only reads the ones the app builds. It files it under the payload's tag,
 * so a message sent twice replaces itself.
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
            .setContentText(note ?: payload.text?.let(::stripDeliveryTags))
            .setStyle(NotificationCompat.BigTextStyle().bigText(note ?: payload.text?.let(::stripDeliveryTags)))
            .setAutoCancel(true)
            .setContentIntent(openIntent(payload, toLink = false))

        // « Pars maintenant » (MAG-658): a strong interruption, the full screen over the lock screen.
        if (payload.isDeparture && withActions) {
            val screen = departureScreen(payload, go = false)
            builder
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(screen)
                .setFullScreenIntent(screen, true)
        }

        val manager = context.getSystemService(NotificationManager::class.java)

        // A message of Maggie (MAG-682) is a conversation, the only form Android Auto reads aloud. A note
        // or a settled request is the app talking, not Maggie: those stay plain text.
        val conversation = payload.isConversation && note == null && withActions
        val folded = if (conversation) {
            val history = history(manager, payload)
            builder
                .setStyle(conversationStyle(payload, history.flatMap { it.second }))
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setGroup(GROUP_CONVERSATION)
            history.map { it.first }
        } else {
            emptyList()
        }

        // The system takes the alert down by itself when its event starts (MAG-679): a tap the next day opens nothing.
        payload.expiresAt?.let { expiresAt ->
            if (withActions) builder.setTimeoutAfter(maxOf(1L, expiresAt.toEpochMilli() - System.currentTimeMillis()))
        }

        if (withActions) {
            payload.actions().forEach { builder.addAction(action(it, payload)) }
            if (conversation) builder.addInvisibleAction(action(PushActionKind.MARK_AS_READ, payload))
        } else {
            builder.setTimeoutAfter(SETTLED_TIMEOUT_MS)
        }

        manager.notify(payload.systemTag, NOTIFICATION_ID, builder.build())
        // The new notification says them again: one conversation in the tray, not one entry per message.
        folded.forEach { manager.cancel(it, NOTIFICATION_ID) }
        return true
    }

    /** The conversation's earlier messages still in the tray, with the tag each sits under, oldest first. */
    private fun history(manager: NotificationManager, payload: PushPayload): List<Pair<String, List<NotificationCompat.MessagingStyle.Message>>> =
        manager.activeNotifications
            .filter { it.notification.channelId == PushChannels.CHAT && it.id == NOTIFICATION_ID && it.tag != null && it.tag != payload.systemTag }
            .mapNotNull { sbn ->
                NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(sbn.notification)
                    ?.let { sbn.tag to it.messages }
            }
            .sortedBy { (_, messages) -> messages.lastOrNull()?.timestamp ?: 0L }

    private fun conversationStyle(
        payload: PushPayload,
        earlier: List<NotificationCompat.MessagingStyle.Message>,
    ): NotificationCompat.MessagingStyle {
        val maggie = Person.Builder()
            .setName(SENDER)
            .setKey(SENDER_KEY)
            .setIcon(IconCompat.createWithResource(context, R.drawable.maggie))
            .build()
        val style = NotificationCompat.MessagingStyle(Person.Builder().setName("Moi").setKey("owner").build())
        earlier.sortedBy { it.timestamp }.takeLast(MAX_HISTORY - 1).forEach(style::addMessage)
        style.addMessage(NotificationCompat.MessagingStyle.Message(spoken(payload), System.currentTimeMillis(), maggie))
        return style
    }

    // Android Auto reads the message, not the title: a headline that is not just « Maggie » goes in front of it.
    private fun spoken(payload: PushPayload): String =
        listOfNotNull(payload.title.takeIf { it != SENDER }, payload.text?.let(::stripDeliveryTags)).joinToString("\n")

    /** [tag]: the payload's [PushPayload.systemTag] — its notification id, or `cooking-timer-{id}`. */
    fun close(tag: String) {
        context.getSystemService(NotificationManager::class.java).cancel(tag, NOTIFICATION_ID)
    }

    /** « Plus tard »: the notification comes back, as it was, after [delayMs]. */
    fun postpone(payload: PushPayload, delayMs: Long = POSTPONE_MS) {
        close(payload.systemTag)
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
        // « Y aller » of a departure hands the navigation link to Waze or Maps; the activity that does it also closes the notification.
        if (kind == PushActionKind.GO && payload.isDeparture) {
            return NotificationCompat.Action.Builder(0, kind.label, departureScreen(payload, go = true)).build()
        }
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

        // « Voir » (MAG-512) opens the tasks the same way, answering the reminder once the app is open.
        if (kind == PushActionKind.OPEN) {
            PushIntents.openReminder(context, payload)?.let { intent ->
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
        if (kind == PushActionKind.MARK_AS_READ) {
            builder
                .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
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

    private fun departureScreen(payload: PushPayload, go: Boolean): PendingIntent = PendingIntent.getActivity(
        context,
        requestCode(payload, if (go) "departure-go" else "departure-screen"),
        PushIntents.departure(context, payload, go),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun requestCode(payload: PushPayload, what: String): Int = (payload.notificationId + what).hashCode()

    companion object {
        /** One notification per tag: a resent message, or a timer ringing twice, replaces itself. */
        const val NOTIFICATION_ID = 0
        const val POSTPONE_MS = 10 * 60 * 1000L

        /** The sender of Maggie's messages, and the group her conversation notifications share. */
        const val SENDER = "Maggie"
        private const val SENDER_KEY = "maggie"
        const val GROUP_CONVERSATION = "maggie-conversation"
        private const val MAX_HISTORY = 6
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
    const val EXTRA_SHORTAGE_ID = "shortageId"
    const val EXTRA_COOKING_TIMER_ID = "cookingTimerId"
    const val EXTRA_EXPIRES_AT = "expiresAt"

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
        intent.putExtra(EXTRA_SHORTAGE_ID, payload.shortageId)
        intent.putExtra(EXTRA_COOKING_TIMER_ID, payload.cookingTimerId)
        intent.putExtra(EXTRA_EXPIRES_AT, payload.expiresAt?.toString())
        if (payload.reminderActions.isNotEmpty()) intent.putExtra(EXTRA_ACTIONS, payload.reminderActions.joinToString(","))
    }

    fun payloadOf(intent: Intent): PushPayload? = PushPayload.from(
        data = listOf(
            EXTRA_NOTIFICATION_ID, EXTRA_TYPE, EXTRA_TITLE, EXTRA_TEXT, EXTRA_LINK, EXTRA_APPROVAL_ID, EXTRA_CHANNEL, EXTRA_INTERACTION,
            EXTRA_REMINDER_ID, EXTRA_TASK_ID, EXTRA_ACTIONS, EXTRA_SHORTAGE_ID, EXTRA_COOKING_TIMER_ID, EXTRA_EXPIRES_AT,
        )
            .mapNotNull { key -> intent.getStringExtra(key)?.let { key to it } }
            .toMap(),
    )

    /** What « Y aller » of a departure asks the departure activity to do: open the navigation link, then close. */
    const val ACTION_DEPARTURE_GO = "com.maggie.app.push.DEPARTURE_GO"

    /** The navigation link of a departure alert: https only, whoever sent the message — Waze and Maps answer to it. */
    fun navigationOf(raw: String?): Uri? = raw?.takeIf(::isNavigable)?.let(Uri::parse)

    /** [navigationOf] without Android, for what only needs to know whether there is a link to open. */
    fun isNavigable(raw: String?): Boolean {
        val uri = raw?.let { runCatching { java.net.URI(it) }.getOrNull() } ?: return false
        return uri.scheme == "https" && !uri.host.isNullOrBlank()
    }

    /** « Y aller » of a departure: the link handed to whatever navigates; null without a valid one. */
    fun navigate(payload: PushPayload): Intent? =
        navigationOf(payload.link)?.let { Intent(Intent.ACTION_VIEW, it).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }

    /**
     * The departure activity: « Pars maintenant » full screen, or with [go] straight to the navigation link.
     * Never reused (no CLEAR_TOP, standard launch mode): each alert reads its own payload.
     */
    fun departure(context: Context, payload: PushPayload, go: Boolean): Intent =
        Intent(context, DepartureActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            if (go) action = ACTION_DEPARTURE_GO
            put(this, payload)
        }

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

    /**
     * « Voir » (MAG-512): the app opened on the payload's own link (the tasks), told to answer the
     * reminder. Null without a link of the app's scheme: the button is then a plain answer.
     */
    fun openReminder(context: Context, payload: PushPayload): Intent? {
        val link = linkOf(payload.link) ?: return null
        return Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            action = Intent.ACTION_VIEW
            data = link
            put(this, payload)
            putExtra(EXTRA_ANSWER, PushActionKind.OPEN.reminderKey)
        }
    }

    /** The reminder's button an intent asks the app to answer, with its push; null for any other intent. */
    fun answerOf(intent: Intent): Pair<PushActionKind, PushPayload>? {
        // The buttons that open the app only, the answers it puts there: the activity is exported, and
        // another app's intent must not tick a task or move it to tomorrow.
        val kind = intent.getStringExtra(EXTRA_ANSWER)?.let(PushActionKind::ofReminderKey)
            ?.takeIf { it == PushActionKind.START || it == PushActionKind.OPEN } ?: return null
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
        val tag = intent.getStringExtra(EXTRA_COOKING_TIMER_ID)?.takeIf { it.isNotBlank() }?.let(::cookingTimerTag) ?: id
        context.getSystemService(NotificationManager::class.java).cancel(tag, PushNotifier.NOTIFICATION_ID)
    }
}

package com.maggie.app.data.interruption

import com.maggie.app.data.fcm.PushChannels
import com.maggie.app.data.fcm.PushInteraction
import com.maggie.app.data.fcm.PushPayload
import com.maggie.app.data.model.Notification
import com.maggie.app.data.model.PendingApproval
import com.maggie.app.ui.navigation.DeepLinks
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant

/** What identifies an interruption: a held action by its approval, whatever notification announced it. */
val PushPayload.key: String get() = approvalId?.let { "approval:$it" } ?: notificationId

/** A question Maggie waits on: it goes ahead of the messages. */
val PushPayload.isQuestion: Boolean get() = approvalId != null || interaction?.kind == "confirm"

/**
 * An `approval` notification announces a held action that is answered as itself, through the
 * approvals feed: raising both would show the same question twice.
 */
val PushPayload.interrupts: Boolean get() = !(type == "approval" && approvalId == null)

/** What one message of a feed means for the queue. */
sealed interface FeedEvent {
    data class Offer(val payload: PushPayload) : FeedEvent

    /** Read, answered, expired or deleted elsewhere: it must not be said any more. */
    data class Withdraw(val key: String) : FeedEvent
}

/** Turns what the API publishes into what the toaster shows — the same targets and words as the push and the admin. */
object Interruptions {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** API collection → app link host and the label of the button that opens it (`PushMessageFactory::ENTITY_LINKS`). */
    private val ENTITY_LINKS = mapOf(
        "events" to ("event" to "Voir l'événement"),
        "tasks" to ("task" to "Voir la tâche"),
        "grocery_items" to ("grocery" to "Voir la liste de courses"),
        "recipes" to ("recipe" to "Voir la recette"),
    )
    private val FINANCE_SCREENS = setOf("", "accounts", "budgets", "categories", "rules", "rule-suggestions", "banks", "cushion", "loans", "review")
    private val ENTITY_PATH = Regex("^/api/([a-z_]+)/([0-9A-Za-z-]{1,64})$")
    private val FINANCE_PATH = Regex("^/finance(?:/([a-z-]+))?/?$")

    /**
     * Null for what is not worth interrupting: already read, nothing to say, or a held action (see [interrupts]).
     * [again] is the owner asking to see it once more, so having read it does not matter.
     */
    fun of(notification: Notification, again: Boolean = false, scheme: String = DeepLinks.SCHEME): PushPayload? {
        if ((notification.isRead && !again) || notification.type == "approval" || notification.title.isBlank()) return null
        val (link, label) = linkOf(notification.relatedEntityIri, scheme)
        return PushPayload(
            notificationId = notification.id,
            type = notification.type,
            channel = PushChannels.forType(notification.type),
            title = notification.title,
            text = messageOf(notification.type, notification.body),
            link = link,
            actionLabel = label,
            approvalId = null,
            interaction = null,
        )
    }

    /** A held action waiting for the owner's answer; null once decided or expired. */
    fun of(approval: PendingApproval, now: Instant = Instant.now()): PushPayload? {
        if (!approval.isPending) return null
        val expiry = approval.expiresAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
        if (expiry != null && !expiry.isAfter(now)) return null
        return PushPayload(
            notificationId = approval.id,
            type = "approval",
            channel = PushChannels.APPROVALS,
            title = "Maggie demande ton accord",
            text = approval.summary?.takeIf { it.isNotBlank() } ?: approval.toolName,
            link = null,
            actionLabel = null,
            approvalId = approval.id,
            interaction = PushInteraction(kind = "confirm"),
        )
    }

    /** One message of `/users/{userId}/api/notifications/{id}`: a creation, a change, or `deleted`. */
    fun readNotification(raw: String): FeedEvent? {
        val data = objectOf(raw) ?: return null
        val id = data["@id"]?.jsonPrimitive?.contentOrNull?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: return null
        if (data["deleted"]?.jsonPrimitive?.booleanOrNull == true || data["readAt"]?.jsonPrimitive?.contentOrNull != null) {
            return FeedEvent.Withdraw(id)
        }
        // A change that carries only some fields cannot be read as a notification: it is not a new one.
        val notification = runCatching { json.decodeFromJsonElement(Notification.serializer(), data) }.getOrNull() ?: return null
        return of(notification)?.let(FeedEvent::Offer)
    }

    /** One message of `/approvals/{userId}`. */
    fun readApproval(raw: String, now: Instant = Instant.now()): FeedEvent? {
        val data = objectOf(raw) ?: return null
        val approval = runCatching { json.decodeFromJsonElement(PendingApproval.serializer(), data) }.getOrNull() ?: return null
        if (!approval.isPending) return FeedEvent.Withdraw("approval:${approval.id}")
        return of(approval, now)?.let(FeedEvent::Offer)
    }

    private fun objectOf(raw: String): JsonObject? =
        runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()

    // A reminder stores how many minutes ahead it fires, not a sentence.
    private fun messageOf(type: String, body: String?): String? {
        val text = body?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return if (type == "reminder" && text.all(Char::isDigit)) "Dans ${text.toInt()} min" else text
    }

    private fun linkOf(iri: String?, scheme: String): Pair<String?, String?> {
        val path = iri?.substringBefore('?')?.substringBefore('#') ?: return null to null

        ENTITY_PATH.matchEntire(path)?.let { match ->
            val (host, label) = ENTITY_LINKS[match.groupValues[1]] ?: return null to null
            return "$scheme://$host/${match.groupValues[2]}" to label
        }
        FINANCE_PATH.matchEntire(path)?.let { match ->
            val screen = match.groupValues[1]
            if (screen !in FINANCE_SCREENS) return null to null
            val label = if (screen == "banks") "Reconnecter la banque" else "Ouvrir les finances"
            return "$scheme://finance${if (screen.isEmpty()) "" else "/$screen"}" to label
        }
        return null to null
    }
}

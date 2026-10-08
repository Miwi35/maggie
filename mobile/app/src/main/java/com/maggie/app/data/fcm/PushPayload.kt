package com.maggie.app.data.fcm

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What Maggie asks of the owner, as the server declares it in `data.interaction` (spec « Notifications push », partie 9). */
@Serializable
data class PushInteraction(
    val kind: String? = null,
    val completable: Boolean = false,
    val options: List<String> = emptyList(),
)

/** One button of a notification, and what it does when pressed. */
enum class PushActionKind(val label: String) {
    REPLY("Répondre"),
    OK("OK"),
    DONE("Fait"),
    LATER("Plus tard"),
    GO("Y aller"),
    APPROVE("Autoriser"),
    DENY("Refuser"),
}

/**
 * The `data` block of an FCM message from `PushMessageFactory`, plus the `notification`
 * block when there is one. Every value is a string; absent keys are absent, never null.
 */
data class PushPayload(
    val notificationId: String,
    val type: String?,
    val channel: String,
    val title: String,
    val text: String?,
    val link: String?,
    val actionLabel: String?,
    val approvalId: String?,
    val interaction: PushInteraction?,
) {
    /**
     * The buttons the notification carries, by what it asks.
     *
     * `interaction.kind` decides when the server provides it; otherwise the type does. A
     * form whose answer has no endpoint yet (a choice, a yes/no that is not an approval)
     * carries no button: tapping the notification opens `link`, as for a series.
     */
    fun actions(): List<PushActionKind> {
        val kind = interaction?.kind
        return when {
            kind == "ack" -> if (interaction?.completable == true) listOf(PushActionKind.DONE, PushActionKind.LATER) else listOf(PushActionKind.OK)
            kind == "action" -> if (link != null) listOf(PushActionKind.GO, PushActionKind.LATER) else listOf(PushActionKind.OK)
            kind == "confirm" -> approvalButtons()
            kind != null -> emptyList()
            type == "approval" -> approvalButtons()
            type == "task_due" -> listOf(PushActionKind.DONE, PushActionKind.LATER)
            type == "reminder" -> listOf(PushActionKind.OK)
            channel == PushChannels.CHAT -> listOf(PushActionKind.REPLY)
            link != null -> listOf(PushActionKind.GO, PushActionKind.LATER)
            else -> listOf(PushActionKind.OK)
        }
    }

    // « Autoriser » / « Refuser » answer a held action: without its id there is nothing to answer.
    private fun approvalButtons(): List<PushActionKind> =
        if (approvalId != null) listOf(PushActionKind.APPROVE, PushActionKind.DENY) else emptyList()

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** Null when the message carries no notification id: nothing to dedupe, answer or close. */
        fun from(
            data: Map<String, String>,
            notificationTitle: String? = null,
            notificationBody: String? = null,
            notificationChannel: String? = null,
        ): PushPayload? {
            val id = data["notificationId"]?.takeIf { it.isNotBlank() } ?: return null
            val type = data["type"]
            val text = (data["body"] ?: data["text"] ?: notificationBody)?.takeIf { it.isNotBlank() }
            val title = (data["title"] ?: notificationTitle)?.takeIf { it.isNotBlank() } ?: "Maggie"
            return PushPayload(
                notificationId = id,
                type = type,
                channel = notificationChannel?.takeIf { it in PushChannels.ALL } ?: PushChannels.forType(type),
                title = title,
                text = text,
                link = data["link"]?.takeIf { it.isNotBlank() },
                actionLabel = data["actionLabel"]?.takeIf { it.isNotBlank() },
                approvalId = data["approvalId"]?.takeIf { it.isNotBlank() },
                interaction = data["interaction"]?.let { raw ->
                    runCatching { json.decodeFromString<PushInteraction>(raw) }.getOrNull()
                },
            )
        }
    }
}

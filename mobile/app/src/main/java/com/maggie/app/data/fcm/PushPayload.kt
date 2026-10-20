package com.maggie.app.data.fcm

import com.maggie.app.ui.navigation.DeepLinks
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What Maggie asks of the owner, as the server declares it in `data.interaction` (spec « Notifications push », partie 9). */
@Serializable
data class PushInteraction(
    val kind: String? = null,
    val completable: Boolean = false,
    val options: List<String> = emptyList(),
)

/**
 * One button of a notification, and what it does when pressed.
 *
 * [reminderKey] is the key a task reminder's button goes by in `data.actions` and in
 * `POST /api/task-reminders/{id}/action` (MAG-510); null for the buttons the app picks itself.
 */
enum class PushActionKind(val label: String, val reminderKey: String? = null) {
    REPLY("Répondre"),
    OK("C'est noté"),
    DONE("Fait"),
    LATER("Plus tard"),
    GO("Y aller"),
    APPROVE("Autoriser"),
    DENY("Refuser"),

    /** « Je m'y mets »: the task's Focus opens, the reminder is answered. */
    START("Je m'y mets", "start"),

    /** « Dans 10 min »: the notification goes, the server sends another in ten minutes. */
    IN_TEN("Dans 10 min", "in10"),

    /** « Ce soir »: the notification goes, the server sends another after dinner. */
    TONIGHT("Ce soir", "tonight"),

    /** « Fait » of a reminder: the task is ticked, and the notification says the bravo. */
    TICK("Fait", "done"),

    /** « Demain »: the task goes into tomorrow's five. */
    TOMORROW("Demain", "tomorrow"),
    ;

    companion object {
        /** The button a reminder's key names; null for a key this version does not know. */
        fun ofReminderKey(key: String): PushActionKind? = entries.firstOrNull { it.reminderKey == key }
    }
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
    /** The task reminder the notification is (MAG-510): its buttons answer it. */
    val reminderId: String? = null,
    /** The reminder's task, whose Focus « Je m'y mets » opens. */
    val taskId: String? = null,
    /** The reminder's buttons as the server orders them (`data.actions`), keys as they came. */
    val reminderActions: List<String> = emptyList(),
) {
    /**
     * The buttons the notification carries, by what it asks.
     *
     * `interaction.kind` decides when the server provides it; otherwise the type does. A
     * form whose answer has no endpoint yet (a choice, a yes/no that is not an approval)
     * carries no button: tapping the notification opens `link`, as for a series.
     *
     * A notification with a `link` carries « C'est noté », the link and « Plus tard » — three
     * buttons, the most Android shows. « C'est noté » says it was seen without opening or
     * postponing it; a task that falls due keeps « Fait », which is its acknowledgement.
     *
     * A task reminder (MAG-510) carries the buttons the server lists, in its order, the three
     * first Android shows; a key the app does not know is skipped, and none listed is none shown.
     */
    fun actions(): List<PushActionKind> {
        val kind = interaction?.kind
        return when {
            reminderId != null -> reminderActions.mapNotNull(PushActionKind::ofReminderKey).distinct().take(MAX_ACTIONS)
            kind == "ack" -> if (interaction?.completable == true) listOf(PushActionKind.DONE, PushActionKind.LATER) else listOf(PushActionKind.OK)
            kind == "action" -> seenOrOpened()
            kind == "confirm" -> approvalButtons()
            kind != null -> emptyList()
            type == "approval" -> approvalButtons()
            type == "task_due" -> listOf(PushActionKind.DONE, PushActionKind.LATER)
            type == "reminder" -> seenOrOpened()
            channel == PushChannels.CHAT -> listOf(PushActionKind.REPLY)
            else -> seenOrOpened()
        }
    }

    private fun seenOrOpened(): List<PushActionKind> =
        if (link != null) listOf(PushActionKind.OK, PushActionKind.GO, PushActionKind.LATER) else listOf(PushActionKind.OK)

    // « Autoriser » / « Refuser » answer a held action: without its id there is nothing to answer.
    private fun approvalButtons(): List<PushActionKind> =
        if (approvalId != null) listOf(PushActionKind.APPROVE, PushActionKind.DENY) else emptyList()

    /** Where « Je m'y mets » goes: the Focus of the reminder's task, null without a task. */
    fun focusLink(): String? = taskId?.takeIf(DeepLinks::isValidId)?.let(DeepLinks::taskFocus)

    companion object {
        /** The buttons Android shows on a notification, at most. */
        const val MAX_ACTIONS = 3

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
                reminderId = data["reminderId"]?.takeIf { it.isNotBlank() },
                taskId = data["taskId"]?.takeIf { it.isNotBlank() },
                reminderActions = data["actions"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() },
            )
        }
    }
}

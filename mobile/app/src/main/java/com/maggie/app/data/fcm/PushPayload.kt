package com.maggie.app.data.fcm

import com.maggie.app.ui.navigation.DeepLinks
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.OffsetDateTime

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

    /** « Voir » of a routine reminder (MAG-512): the app opens on the tasks, the reminder is answered. */
    OPEN("Voir", "open"),

    /** « Ajouter à la liste » of a shortage alert (MAG-570): the missing product goes on the shopping list. */
    ADD_TO_LIST("Ajouter à la liste", "add"),

    /** « Déjà en route » of a departure alert (MAG-658): the notification goes, nothing opens. */
    ALREADY_ON_THE_WAY("Déjà en route"),

    /** « Marquer comme lu » of a conversation (MAG-682): never shown in the tray, Android Auto presses it after reading aloud. */
    MARK_AS_READ("Marquer comme lu"),
    ;

    companion object {
        /** The button a reminder's key names; null for a key this version does not know. */
        fun ofReminderKey(key: String): PushActionKind? = entries.firstOrNull { it.reminderKey == key }
    }
}

/**
 * The `data` block of an FCM message from `PushMessageFactory`, which is data alone (MAG-681).
 * Every value is a string; absent keys are absent, never null.
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
    /** The shortage alert the notification is (MAG-570): « Ajouter à la liste » answers it. */
    val shortageId: String? = null,
    /** The cooking timer that rang (MAG-582): one notification per timer, cleared once it is seen elsewhere. */
    val cookingTimerId: String? = null,
    /** From then on the alert is no longer worth saying (MAG-679): the event started, or its trip is gone. */
    val expiresAt: Instant? = null,
    /** The chat message a `message` push announces (MAG-687); its link opens the conversation on it. */
    val messageId: String? = null,
    /** Quiet hours (MAG-687): posted without sound or vibration. */
    val silent: Boolean = false,
) {
    /** Delivered late (a push held back for hours), an alert is over once its [expiresAt] has passed. */
    fun isExpired(now: Instant): Boolean = expiresAt != null && !expiresAt.isAfter(now)

    /** A message Maggie wrote in the conversation (MAG-687): a tap goes to it, nothing is said while the chat shows it. */
    val isMessage: Boolean get() = type == MESSAGE_TYPE

    /** « Pars maintenant » (MAG-655): the alert that opens a full-screen interruption and a navigation app. */
    val isDeparture: Boolean get() = type == DEPARTURE_TYPE
    /** The system tag the app files it under, and the one a withdrawal names — its notification id, or `cooking-timer-{id}`. */
    /** The system tag it is shown under — the server's own, so the app's and the system's are one notification. */
    val systemTag: String get() = cookingTimerId?.let(::cookingTimerTag) ?: notificationId

    /** A message of Maggie that can be answered (MAG-682): shown as a conversation, which is what Android Auto reads aloud. */
    val isConversation: Boolean get() = PushActionKind.REPLY in actions()

    /**
     * What Maggie waits an answer for (MAG-683): a validation, a reminder, a task or a shortage to settle.
     * A plain message, a departure alert or a mere « C'est noté » waits for nothing, and is never told twice.
     */
    val awaitsAnswer: Boolean
        get() = reminderId != null || shortageId != null || actions().any { it in ANSWERING }

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
     *
     * A shortage alert (MAG-570) carries « Ajouter à la liste » and « Plus tard ».
     *
     * A departure alert (MAG-658) carries « Y aller » when it has a navigation link, then « Déjà en route ».
     */
    fun actions(): List<PushActionKind> {
        val kind = interaction?.kind
        return when {
            reminderId != null -> reminderActions.mapNotNull(PushActionKind::ofReminderKey).distinct().take(MAX_ACTIONS)
            shortageId != null -> listOf(PushActionKind.ADD_TO_LIST, PushActionKind.LATER)
            isDeparture -> listOfNotNull(PushActionKind.GO.takeIf { PushIntents.isNavigable(link) }, PushActionKind.ALREADY_ON_THE_WAY)
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
        /** `PushMessageFactory::COOKING_TIMER_TAG`, followed by the timer's id. */
        const val COOKING_TIMER_TAG = "cooking-timer"

        /** `PushMessageFactory::LOCATION_REQUEST_TYPE` (MAG-650): data-only, the phone answers with its position. */
        const val LOCATION_REQUEST_TYPE = "location_request"

        /** `NotificationType::Message` (MAG-687). */
        const val MESSAGE_TYPE = "message"

        /** `NotificationType::Departure`. */
        const val DEPARTURE_TYPE = "departure"

        private val ANSWERING = setOf(
            PushActionKind.DONE, PushActionKind.LATER, PushActionKind.APPROVE, PushActionKind.DENY, PushActionKind.ADD_TO_LIST,
        )

        /** The buttons Android shows on a notification, at most. */
        const val MAX_ACTIONS = 3

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** The API writes ISO-8601 with an offset (`+00:00`); null for anything else. */
        fun parseInstant(raw: String?): Instant? =
            raw?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() }

        /** Null when the message carries no notification id (nothing to dedupe, answer or close) or is a `location_request`, which is never shown. */
        fun from(data: Map<String, String>): PushPayload? {
            if (data["type"] == LOCATION_REQUEST_TYPE) return null
            val id = data["notificationId"]?.takeIf { it.isNotBlank() } ?: return null
            val type = data["type"]
            val text = (data["body"] ?: data["text"])?.takeIf { it.isNotBlank() }
            val title = data["title"]?.takeIf { it.isNotBlank() } ?: "Maggie"
            return PushPayload(
                notificationId = id,
                type = type,
                channel = data["channel"]?.takeIf { it in PushChannels.ALL } ?: PushChannels.forType(type),
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
                shortageId = data["shortageId"]?.takeIf { it.isNotBlank() },
                cookingTimerId = data["cookingTimerId"]?.takeIf { it.isNotBlank() },
                expiresAt = parseInstant(data["expiresAt"]),
                messageId = data["messageId"]?.takeIf { it.isNotBlank() },
                silent = data["silent"] == "1",
                reminderActions = data["actions"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() },
            )
        }
    }
}

/** The tag of a cooking timer's end (MAG-582): `cooking-timer-{timerId}`, as the server gives it. */
fun cookingTimerTag(timerId: String): String = "${PushPayload.COOKING_TIMER_TAG}-$timerId"

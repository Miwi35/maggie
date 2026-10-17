package com.maggie.app.data.api

import com.maggie.app.BuildConfig
import com.maggie.app.data.model.AcceptRecurringSuggestionsRequest
import com.maggie.app.data.model.AcceptRecurringSuggestionsResult
import com.maggie.app.data.model.AccountIncident
import com.maggie.app.data.model.AccountIncidents
import com.maggie.app.data.model.Agenda
import com.maggie.app.data.model.AgUiEvent
import com.maggie.app.data.model.ChatMessage
import com.maggie.app.data.model.Contact
import com.maggie.app.data.model.ContactCounterparty
import com.maggie.app.data.model.ContactCounterpartyCreateRequest
import com.maggie.app.data.model.ContactCreateRequest
import com.maggie.app.data.model.CounterpartySuggestion
import com.maggie.app.data.model.Context
import com.maggie.app.data.model.CiqualFood
import com.maggie.app.data.model.Errand
import com.maggie.app.data.model.Event
import com.maggie.app.data.model.EventReminders
import com.maggie.app.data.model.GoogleCalendar
import com.maggie.app.data.model.GroceryList
import com.maggie.app.data.model.Ingredient
import com.maggie.app.data.model.Meal
import com.maggie.app.data.model.MyShare
import com.maggie.app.data.model.MyShareRequest
import com.maggie.app.data.model.PeerDebt
import com.maggie.app.data.model.PeerDebtSummary
import com.maggie.app.data.model.TransactionDebt
import com.maggie.app.data.model.TransactionDebtRequest
import com.maggie.app.data.model.MealGroceryPreview
import com.maggie.app.data.model.MealSettings
import com.maggie.app.data.model.MealSettingsValues
import com.maggie.app.data.model.Notification
import com.maggie.app.data.model.PendingApproval
import com.maggie.app.data.model.Proaction
import com.maggie.app.data.model.Aisle
import com.maggie.app.data.model.Product
import com.maggie.app.data.model.ProductAisle
import com.maggie.app.data.model.Qualification
import com.maggie.app.data.model.qualificationPatch
import com.maggie.app.data.model.Recipe
import com.maggie.app.data.model.Utensil
import com.maggie.app.data.model.Account
import com.maggie.app.data.model.AcceptRuleSuggestionsRequest
import com.maggie.app.data.model.AcceptRuleSuggestionsResult
import com.maggie.app.data.model.ApplyRulesResult
import com.maggie.app.data.model.BankAuthorization
import com.maggie.app.data.model.BankConnection
import com.maggie.app.data.model.BankConnectionsResponse
import com.maggie.app.data.model.BankSyncResult
import com.maggie.app.data.model.BudgetStatus
import com.maggie.app.data.model.EnvelopeAdjustments
import com.maggie.app.data.model.EnvelopeAdjustmentsRequest
import com.maggie.app.data.model.EnvelopeAdjustmentsResult
import com.maggie.app.data.model.CategorizationRule
import com.maggie.app.data.model.Season
import com.maggie.app.data.model.ONE_OFF_MODE
import com.maggie.app.data.model.targetsCategory
import com.maggie.app.data.model.RecurringAttachReport
import com.maggie.app.data.model.RulePreview
import com.maggie.app.data.model.RulePreviewRequest
import com.maggie.app.data.model.RecurringAttachRequest
import com.maggie.app.data.model.RecurringOperationPreview
import com.maggie.app.data.model.RecurringOperationPreviewRequest
import com.maggie.app.data.model.RecurringOperationRecord
import com.maggie.app.data.model.RecurringOperationsOverview
import com.maggie.app.data.model.RecurringSuggestion
import com.maggie.app.data.model.RecurringSuggestionsResponse
import com.maggie.app.data.model.recurringOperationIri
import com.maggie.app.data.model.RuleSuggestion
import com.maggie.app.data.model.SortRequest
import com.maggie.app.data.model.SortResult
import com.maggie.app.data.model.ToSortPile
import com.maggie.app.data.model.RuleSuggestionsResponse
import com.maggie.app.data.model.CushionStatus
import com.maggie.app.data.model.SalaryExpectedDayRequest
import com.maggie.app.data.model.SavingsAdvances
import com.maggie.app.data.model.TreasuryOutlook
import com.maggie.app.data.model.TreasurySettings
import com.maggie.app.data.model.TreasurySettingsResult
import com.maggie.app.data.model.DayPlan
import com.maggie.app.data.model.DayPlanUpdateRequest
import com.maggie.app.data.model.DayProposal
import com.maggie.app.data.model.DayReview
import com.maggie.app.data.model.DayReviewUpdateRequest
import com.maggie.app.data.model.DebtTimeline
import com.maggie.app.data.model.FinanceDashboard
import com.maggie.app.data.model.UpcomingProjection
import com.maggie.app.data.model.MonthBalance
import com.maggie.app.data.model.Loan
import com.maggie.app.data.model.MonthlyReview
import com.maggie.app.data.model.Category
import com.maggie.app.data.model.Store
import com.maggie.app.data.model.Envelope
import com.maggie.app.data.model.RollOverResult
import com.maggie.app.data.model.Transaction
import com.maggie.app.data.model.TransferCandidates
import com.maggie.app.data.model.TransferInfo
import com.maggie.app.data.model.TransferLeg
import com.maggie.app.data.model.TransferUpdateRequest
import com.maggie.app.data.model.TtsVoice
import com.maggie.app.data.model.RecurringFrequency
import com.maggie.app.data.model.RecurringGroceryItem
import com.maggie.app.data.model.PurchaseSuggestion
import com.maggie.app.data.model.apiValue
import com.maggie.app.data.model.SearchResponse
import com.maggie.app.data.model.Task
import com.maggie.app.data.model.TaskOrderPreviewItem
import com.maggie.app.data.model.TaskSettings
import com.maggie.app.data.model.TaskViewResponse
import com.maggie.app.data.model.User
import com.maggie.app.data.model.UserPreference
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.accept
import io.ktor.client.request.delete
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withTimeout
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.net.ConnectException
import java.net.UnknownHostException

/** A meal that goes with the recipe: its day (`yyyy-MM-dd`) and slot (`lunch`/`dinner`). */
@Serializable
data class PlannedMealRef(val date: String, val slot: String)

/** What deleting a recipe takes with it: the meals it is the only recipe of (MAG-289). */
@Serializable
data class RecipeDeletionImpact(val mealCount: Int = 0, val meals: List<PlannedMealRef> = emptyList())

@Serializable
data class ApiCollection<T>(val member: List<T> = emptyList(), val totalItems: Int? = null)

/**
 * A timed event sends [startAt]/[endAt], an all-day one [startDate]/[endDate]
 * (`YYYY-MM-DD`, the end exclusive as in Google) and no instant (MAG-382). Null fields are left
 * out of the body: the client's JSON does not encode defaults.
 */
@Serializable
data class EventCreateRequest(
    val summary: String,
    val startAt: String? = null,
    val endAt: String? = null,
    val startDate: String? = null,
    val endDate: String? = null,
    val allDay: Boolean = false,
    val description: String? = null,
    val location: String? = null,
    val timeZone: String = "Europe/Paris",
    val agenda: String? = null,
    val rrule: String? = null,
    val recurringEvent: String? = null,
    val originalStartAt: String? = null,
    val reminders: EventReminders? = null,
    val status: String = "confirmed",
)

@Serializable
data class TaskCreateRequest(
    val title: String,
    val description: String? = null,
    val priority: String = "medium",
    val criticality: String = "low",
    /** A day, `YYYY-MM-DD` (MAG-404). */
    val dueDate: String? = null,
    /** The parent's IRI, for a subtask: the API puts it last among its siblings. */
    val parent: String? = null,
    /** 1 to 1440 minutes (MAG-497). */
    val estimatedMinutes: Int? = null,
    /** The first concrete gesture (MAG-497). */
    val firstStep: String? = null,
)

@Serializable
data class AgendaCreateRequest(
    val name: String,
    val color: String? = null,
    val description: String? = null,
)

@Serializable
// The name and the colour are Google's to give: the API reads them from the
// calendar list entry, so sending them here would only disagree with it
// (MAG-148).
data class GoogleCalendarImportRequest(
    val googleCalendarId: String,
)

@Serializable
data class MessageContextResponse(val messages: List<ChatMessage> = emptyList())

@Serializable
data class AgentChatRequest(
    val message: String,
    val user_id: String = "default",
    /**
     * What the screen behind the assistant overlay was showing (MAG-30).
     *
     * Beside the message, not inside it: the agent stores and publishes `message`, and
     * that is what every client draws the user's bubble from — a block glued to the
     * question is a bubble full of the page it was about.
     */
    val screen_context: String? = null,
    /**
     * One per message composed (MAG-344): a request sent again after a connection error
     * carries the same key, so the agent that already received it answers once.
     */
    val idempotency_key: String? = null,
    /** The assist screenshot, for this turn only (MAG-214). Omitted when null: defaults are not encoded. */
    val image: ChatImage? = null,
    /** The last message of the user, which this one corrects: the agent removes it and its answer (MAG-320). */
    val replaces: String? = null,
)

/** An image as the agent takes it: standard base64, no `data:` prefix. */
@Serializable
data class ChatImage(
    val media_type: String,
    val data: String,
) {
    companion object {
        fun jpeg(bytes: ByteArray) = ChatImage("image/jpeg", java.util.Base64.getEncoder().encodeToString(bytes))
    }
}

@Serializable
data class InterruptChatRequest(
    val messageId: String? = null,
    val spokenText: String,
)

/** [rating] is `up` or `down` ([com.maggie.app.data.model.MessageFeedback]) (MAG-23). */
@Serializable
data class MessageFeedbackRequest(
    val rating: String,
    val comment: String? = null,
)

@Serializable
data class AgentChatResponse(
    val response: String,
    val messages: List<ChatMessage> = emptyList(),
)

@Serializable
data class TranscribeResponse(
    val raw: String = "",
    val clean: String = "",
)

/**
 * What `POST /agent/transcribe` may do to the transcript (MAG-222). [NONE] never
 * reaches the model; [AUTO] lets the fast model tidy a text that needs it, for a
 * dictation destined to be written as is.
 */
enum class TranscriptCleanup(val wire: String) {
    NONE("none"),
    AUTO("auto"),
}

@Serializable
data class TtsSynthesizeRequest(
    val text: String,
    val voice: String,
)

@Serializable
data class TtsVoiceResponse(
    val voice: String,
)

@Serializable
data class PersonalityResponse(
    val name: String = "",
)

@Serializable
data class FcmTokenRequest(
    val token: String,
    val deviceName: String? = null,
)

@Serializable
data class IngredientCreateRequest(
    val name: String,
    val defaultUnit: String? = null,
    val category: String = "other",
    val ciqualAlimCode: String? = null,
    val kcalPer100g: Float? = null,
    val proteinPer100g: Float? = null,
    val carbsPer100g: Float? = null,
    val fatPer100g: Float? = null,
)

/**
 * The day (`YYYY-MM-DD`) and the slot — a meal carries no time (MAG-251). No agenda:
 * the API files every meal in the user's « Repas » module agenda (MAG-324), and no
 * title: it names the meal after its recipes (MAG-378).
 */
@Serializable
data class MealCreateRequest(
    val date: String,
    val slot: String,
    val recipes: List<String> = emptyList(),
    // Absent: the meal follows the user's default (MAG-482).
    val guests: Int? = null,
)

/**
 * What the meal's sheet saves (MAG-378): the whole menu, and the title the owner typed. A blank
 * [customTitle] puts the title back to the recipes' names.
 */
@Serializable
data class MealUpdateRequest(
    val recipes: List<String>,
    val customTitle: String,
)

@Serializable
data class MealGroceryItemsRequest(val ingredients: List<MealGroceryItemChoice>)

@Serializable
data class MealGroceryItemChoice(val ingredientId: String)

@Serializable
data class RecurringGroceryItemCreateRequest(
    val product: String? = null,
    val customLabel: String? = null,
    val quantity: Float? = null,
    val unit: String? = null,
    val frequency: String,
)

@Serializable
data class ProductCreateRequest(
    val name: String,
    val category: String,
    val defaultUnit: String? = null,
    val preferredStore: String? = null,
    val fallbackStore: String? = null,
    val shelfLifeDays: Int? = null,
)

@Serializable
data class StoreCreateRequest(
    val name: String,
    val description: String? = null,
    val visitOrder: Int = 0,
)

@Serializable
data class AccountCreateRequest(
    val name: String,
    val type: String = "checking",
    val bank: String? = null,
    val currency: String = "EUR",
    val balanceCents: Int = 0,
    val isCushion: Boolean = false,
)

@Serializable
data class CategoryCreateRequest(
    val name: String,
    val obligation: String? = null,
    val passiveIncome: Boolean = false,
    val parent: String? = null,
    val color: String? = null,
    val icon: String? = null,
)

@Serializable
data class TransactionCreateRequest(
    val account: String,
    val amountCents: Int,
    val label: String,
    val category: String? = null,
    val currency: String = "EUR",
    val status: String = "spent",
    // yyyy-MM-dd; left out, the API books it today
    val bookedAt: String? = null,
    // Left out, the category's own flags apply (the API reads « none » as « nothing said »).
    val qualification: Qualification? = null,
)

@Serializable
data class EnvelopeCreateRequest(
    val category: String,
    val amountCents: Int,
    val year: Int,
    val mode: String = "monthly",
    val month: Int? = null,
    val currency: String = "EUR",
    val alertThresholdPercent: Int? = null,
    // Monthly only: the amounts of stretches of the year, amountCents being the rest (MAG-415).
    val seasonalAmounts: List<Season> = emptyList(),
)

/** A one-off envelope (Hellfest 2026): a name and an amount, no category and no period. */
@Serializable
data class OneOffEnvelopeCreateRequest(
    val name: String,
    val amountCents: Int,
    // No default: kotlinx leaves defaults out of the body, and the API's own default is « monthly ».
    val mode: String,
    val currency: String = "EUR",
)

/**
 * What the tablet's editor writes back to an envelope (MAG-419). A monthly envelope keeps its month
 * and its seasons; an annual one has neither (the API refuses seasons off a monthly envelope, 400);
 * a one-off one is its [name] and its amount. A null [alertThresholdPercent] puts the default back.
 */
data class EnvelopeUpdateRequest(
    val amountCents: Int,
    val mode: String,
    val month: Int? = null,
    val seasonalAmounts: List<Season> = emptyList(),
    val alertThresholdPercent: Int? = null,
    val name: String? = null,
) {
    /** The merge-patch body: nulls are spelled out, because absent would mean « unchanged ». */
    fun toJson(): JsonObject = buildJsonObject {
        put("amountCents", JsonPrimitive(amountCents))
        if (mode == ONE_OFF_MODE) {
            name?.let { put("name", JsonPrimitive(it)) }
            return@buildJsonObject
        }
        put("mode", JsonPrimitive(mode))
        put("month", month?.let(::JsonPrimitive) ?: JsonNull)
        put("alertThresholdPercent", alertThresholdPercent?.let(::JsonPrimitive) ?: JsonNull)
        put(
            "seasonalAmounts",
            buildJsonArray {
                seasonalAmounts.forEach { season ->
                    add(
                        buildJsonObject {
                            put("fromMonth", JsonPrimitive(season.fromMonth))
                            put("toMonth", JsonPrimitive(season.toMonth))
                            put("amountCents", JsonPrimitive(season.amountCents))
                        },
                    )
                }
            },
        )
    }
}

@Serializable
data class EnvelopeSplitChild(
    val envelopeId: String,
    val amountCents: Int,
)

@Serializable
data class EnvelopeSplitRequest(
    val children: List<EnvelopeSplitChild>,
)

@Serializable
data class RetrospectRequest(
    val retrospect: String,
)

@Serializable
data class LoanCreateRequest(
    val name: String,
    val principalRemainingCents: Int,
    val monthlyPaymentCents: Int,
    val annualRateBasisPoints: Int = 0,
    val lender: String? = null,
    val priority: Int = 0,
    val currency: String = "EUR",
)

@Serializable
data class CushionConfigRequest(
    val targetMonths: Int? = null,
    val monthlyNetIncomeCents: Int? = null,
    val rechargeCapCents: Int? = null,
    val rechargeTargetMonths: Int? = null,
)

@Serializable
data class TreasurySettingsRequest(
    val overdraftLimitCents: Int? = null,
    val salaryExpectedDay: Int? = null,
)

@Serializable
data class RollOverRequest(
    val fromYear: Int,
    val year: Int,
    val fromMonth: Int? = null,
    val month: Int? = null,
    val useActualSpending: Boolean = false,
)

@Serializable
data class CategorizationRuleCreateRequest(
    val labelPattern: String? = null,
    val category: String,
    // No default: it is always sent, with the category.
    val qualification: Qualification,
    val matchType: String = "contains",
    val direction: String = "any",
    val minAmountCents: Int? = null,
    val maxAmountCents: Int? = null,
    val priority: Int = 0,
    val isActive: Boolean = true,
    // Files the existing history under the rule too; written only when true.
    val applyToExisting: Boolean = false,
)

/**
 * A recurring operation as the form writes it. No defaults: the client does not encode them,
 * and a PATCH must be able to clear `endsOn` or `labelPattern` by sending null.
 */
@Serializable
data class RecurringOperationWriteRequest(
    val label: String,
    val counterpartyName: String?,
    val labelPattern: String?,
    val account: String,
    val category: String,
    val qualification: Qualification,
    val period: String,
    val dayRule: String,
    val anchorOn: String,
    val referenceAmountCents: Int,
    val referenceSource: String,
    val amountTolerancePercent: Int,
    val dateToleranceDays: Int,
    val endsOn: String?,
)

/** Attach a transaction to a due date of a series, or detach it with two nulls. */
@Serializable
data class TransactionRecurrenceRequest(
    val recurringOperation: String?,
    val recurringOccurrenceOn: String?,
)

@Serializable
data class AddGroceryItemRequest(
    val label: String,
    val quantity: Float? = null,
    val unit: String? = null,
    val storeId: String? = null,
    val storeName: String? = null,
    val category: String? = null,
)

@Serializable
data class AddGroceryItemResponse(
    val success: Boolean,
    val itemCount: Int,
)

@Serializable
data class EditGroceryItemRequest(
    val label: String? = null,
    val quantity: Float? = null,
    val unit: String? = null,
    val storeId: String? = null,
    val storeName: String? = null,
    val category: String? = null,
)

@Serializable
data class ReorderGroceryItemsRequest(
    val items: List<ReorderEntry>,
)

@Serializable
data class ReorderEntry(
    val id: String,
    val position: Int,
)

@Serializable
data class EndErrandRequest(
    val storeId: String? = null,
)

@Serializable
data class EndErrandRemainingItem(
    val id: String,
    val label: String,
    val quantity: Float? = null,
    val unit: String? = null,
    val store: EndErrandRemainingStore? = null,
)

/** `store` is the store's IRI; `position` left out puts the aisle last. */
@Serializable
data class AisleCreateRequest(
    val store: String,
    val name: String,
)

@Serializable
data class ProductAislesRequest(
    val aisles: List<ProductAisle>,
)

@Serializable
data class EndErrandRemainingStore(
    val id: String,
    val name: String,
)

@Serializable
data class EndErrandRestockedProduct(
    val id: String,
    val name: String,
    val stockState: String? = null,
)

@Serializable
data class EndErrandResponse(
    val success: Boolean,
    val remainingItems: List<EndErrandRemainingItem> = emptyList(),
    val remainingCount: Int = 0,
    val restockedProducts: List<EndErrandRestockedProduct> = emptyList(),
)

/** The agent refused a decision: 404 (not yours), 409 (already decided) or 410 (expired) mean it can no longer be answered. */
class ApprovalDecisionException(val status: Int) : Exception("HTTP $status") {
    val isFinal: Boolean get() = status == 404 || status == 409 || status == 410
}

/**
 * The server turned a write down and said why (422 on a rule of the model, such as a fourth
 * category level): [message] is its own sentence, fit to show as it is.
 */
class ApiRefusalException(val status: Int, message: String) : Exception(message)

private val REFUSAL_JSON = Json { ignoreUnknownKeys = true; isLenient = true }

/** The `detail` (or `hydra:description`, `message`, `error`) of an error body, falling back to a generic sentence. */
internal fun refusalMessage(status: Int, body: String): String {
    val parsed = runCatching { REFUSAL_JSON.parseToJsonElement(body).jsonObject }.getOrNull()
    val text = listOf("detail", "hydra:description", "message", "error")
        .firstNotNullOfOrNull { key -> (parsed?.get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } }
    return text ?: "Le serveur a refusé la modification (HTTP $status)."
}

private val MERGE_PATCH = ContentType("application", "merge-patch+json")

class MaggieApiService(
    val client: HttpClient,
) {
    private val baseUrl = BuildConfig.API_BASE_URL

    private companion object {
        // The next request waits for the interruption to be recorded: it must not wait forever.
        const val INTERRUPT_TIMEOUT_MS = 5_000L
    }

    /**
     * Asks for the whole collection in one response. API Platform serves 30 members a page and the
     * app reads no further, so a displayed list (the agenda, the tasks...) lost its 31st item
     * (MAG-430). Infinite-scroll screens do not call this.
     */
    private fun HttpRequestBuilder.withoutPagination() {
        url.parameters.append("pagination", "false")
    }

    /**
     * The events that overlap the period: [periodStart] and [periodEnd] are ISO-8601 instants with
     * their offset (`2026-10-01T00:00:00+02:00`). An event belongs to the period when it ends
     * after it starts and starts before it ends.
     */
    suspend fun getEvents(periodStart: String, periodEnd: String): List<Event> {
        return client.get("$baseUrl/api/events") {
            accept(ContentType("application", "ld+json"))
            withoutPagination()
            url.parameters.append("endAt[after]", periodStart)
            url.parameters.append("startAt[before]", periodEnd)
        }.body<ApiCollection<Event>>().member
    }

    suspend fun getEvent(id: String): Event {
        return client.get("$baseUrl/api/events/$id") {
            accept(ContentType("application", "ld+json"))
        }.body()
    }

    suspend fun getRecurringEventsBefore(before: String): List<Event> {
        return client.get("$baseUrl/api/events") {
            accept(ContentType("application", "ld+json"))
            withoutPagination()
            url.parameters.append("exists[rrule]", "true")
            url.parameters.append("startAt[strictly_before]", before)
        }.body<ApiCollection<Event>>().member
    }

    suspend fun createEvent(request: EventCreateRequest): Event {
        return client.post("$baseUrl/api/events") {
            contentType(ContentType.Application.Json)
            accept(ContentType("application", "ld+json"))
            setBody(request)
        }.body()
    }

    suspend fun updateEvent(id: String, data: JsonObject): Event {
        return client.patch("$baseUrl/api/events/$id") {
            contentType(MERGE_PATCH)
            accept(ContentType("application", "ld+json"))
            setBody(data)
        }.body()
    }

    suspend fun deleteEvent(id: String) {
        client.delete("$baseUrl/api/events/$id")
    }

    suspend fun getTasks(): List<Task> {
        return client.get("$baseUrl/api/tasks") {
            accept(ContentType("application", "ld+json"))
            withoutPagination()
        }.body<ApiCollection<Task>>().member
    }

    suspend fun getTask(id: String): Task {
        return client.get("$baseUrl/api/tasks/$id") {
            accept(ContentType("application", "ld+json"))
        }.body()
    }

    suspend fun getUndoneTasks(dueDateBefore: String? = null): List<Task> {
        return client.get("$baseUrl/api/tasks") {
            accept(ContentType("application", "ld+json"))
            withoutPagination()
            url.parameters.append("exists[completedAt]", "false")
            dueDateBefore?.let { url.parameters.append("dueDate[before]", it) }
        }.body<ApiCollection<Task>>().member
    }

    /** A task's subtasks, in Google's order (MAG-404). */
    suspend fun getSubtasks(parentIri: String): List<Task> {
        return client.get("$baseUrl/api/tasks") {
            accept(ContentType("application", "ld+json"))
            url.parameters.append("parent", parentIri)
            url.parameters.append("order[position]", "asc")
        }.body<ApiCollection<Task>>().member
    }

    /** The tasks of « Aujourd'hui »: a read-only view, in the order to show (MAG-405). */
    suspend fun getTodayTasks(): List<Task> {
        return client.get("$baseUrl/api/tasks/view/today") {
            accept(ContentType("application", "ld+json"))
        }.body<ApiCollection<Task>>().member
    }

    /** One read of a task view (MAG-408); only « done » is paged, from [page] 1. */
    suspend fun getTaskView(view: String, page: Int? = null): TaskViewResponse {
        return client.get("$baseUrl/api/tasks/view/$view") {
            accept(ContentType("application", "ld+json"))
            page?.let { url.parameters.append("page", it.toString()) }
        }.body()
    }

    // The day (MAG-497): plain JSON; a refused list (400: six slots, a task twice) carries its reason in `error`.
    suspend fun getDayPlan(date: String): DayPlan {
        return client.get("$baseUrl/api/day-plans/$date") {
            accept(ContentType.Application.Json)
        }.orRefusal().body()
    }

    /** The morning's five for [date] (MAG-498), read without writing anything. */
    suspend fun getDayProposal(date: String): DayProposal {
        return client.get("$baseUrl/api/day-plans/$date/proposal") {
            accept(ContentType.Application.Json)
        }.orRefusal().body()
    }

    /** Replaces the day's whole list of slots, in order; the answer is the day as [getDayPlan] reads it. */
    suspend fun updateDayPlan(date: String, request: DayPlanUpdateRequest): DayPlan {
        return client.put("$baseUrl/api/day-plans/$date") {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(request)
        }.orRefusal().body()
    }

    /** The evening's review of [date] (MAG-500), created empty on its first read. */
    suspend fun getDayReview(date: String): DayReview {
        return client.get("$baseUrl/api/day-reviews/$date") {
            accept(ContentType.Application.Json)
        }.orRefusal().body()
    }

    /** « Bonne nuit »: what helped, the whole list; the review is closed. */
    suspend fun updateDayReview(date: String, request: DayReviewUpdateRequest): DayReview {
        return client.put("$baseUrl/api/day-reviews/$date") {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(request)
        }.orRefusal().body()
    }

    suspend fun createTask(request: TaskCreateRequest): Task {
        return client.post("$baseUrl/api/tasks") {
            contentType(ContentType.Application.Json)
            accept(ContentType("application", "ld+json"))
            setBody(request)
        }.body()
    }

    suspend fun updateTask(id: String, data: JsonObject): Task {
        return client.patch("$baseUrl/api/tasks/$id") {
            contentType(MERGE_PATCH)
            accept(ContentType("application", "ld+json"))
            setBody(data)
        }.body()
    }

    suspend fun deleteTask(id: String) {
        client.delete("$baseUrl/api/tasks/$id")
    }

    suspend fun getAgendas(): List<Agenda> {
        return client.get("$baseUrl/api/agendas") {
            accept(ContentType("application", "ld+json"))
            withoutPagination()
        }.body<ApiCollection<Agenda>>().member
    }

    suspend fun getAgenda(id: String): Agenda {
        return client.get("$baseUrl/api/agendas/$id") {
            accept(ContentType("application", "ld+json"))
        }.body()
    }

    suspend fun createAgenda(request: AgendaCreateRequest): Agenda {
        return client.post("$baseUrl/api/agendas") {
            contentType(ContentType.Application.Json)
            accept(ContentType("application", "ld+json"))
            setBody(request)
        }.body()
    }

    suspend fun updateAgenda(id: String, data: JsonObject): Agenda {
        return client.patch("$baseUrl/api/agendas/$id") {
            contentType(MERGE_PATCH)
            accept(ContentType("application", "ld+json"))
            setBody(data)
        }.body()
    }

    suspend fun deleteAgenda(id: String) {
        client.delete("$baseUrl/api/agendas/$id")
    }

    // Google Calendar
    suspend fun getGoogleCalendars(): List<GoogleCalendar> {
        return client.get("$baseUrl/api/calendar/google/calendars") {
            accept(ContentType.Application.Json)
        }.body()
    }

    suspend fun importGoogleCalendar(request: GoogleCalendarImportRequest): Agenda {
        return client.post("$baseUrl/api/calendar/google/import") {
            contentType(ContentType.Application.Json)
            accept(ContentType("application", "ld+json"))
            setBody(request)
        }.body()
    }

    suspend fun exportToGoogleCalendar(agendaId: String) {
        client.post("$baseUrl/api/calendar/google/export") {
            contentType(ContentType.Application.Json)
            setBody(mapOf("agendaId" to agendaId))
        }
    }

    // User
    suspend fun getMe(): User {
        return client.get("$baseUrl/api/users/me") {
            accept(ContentType("application", "ld+json"))
        }.body()
    }

    suspend fun updateUser(id: String, data: JsonObject): User {
        return client.patch("$baseUrl/api/users/$id") {
            contentType(MERGE_PATCH)
            accept(ContentType("application", "ld+json"))
            setBody(data)
        }.body()
    }

    // User Preferences
    suspend fun getUserPreferences(): UserPreference {
        return client.get("$baseUrl/api/user_preferences/me") {
            accept(ContentType("application", "ld+json"))
        }.body()
    }

    suspend fun updateUserPreferences(data: JsonObject): UserPreference {
        return client.patch("$baseUrl/api/user_preferences/me") {
            contentType(MERGE_PATCH)
            accept(ContentType("application", "ld+json"))
            setBody(data)
        }.body()
    }

    // Task settings (MAG-407): the order levels and the alert schedule. Plain JSON, not
    // API Platform; a refused write (400) carries its reason in `error`.
    suspend fun getTaskSettings(): TaskSettings {
        return client.get("$baseUrl/api/task-settings/me") {
            accept(ContentType.Application.Json)
        }.orRefusal().body()
    }

    /**
     * A partial write: only the keys of [changes] (e.g. `{"alertSchedule":{"high":{"overdueEveryDays":null}}}`)
     * change; the answer is the whole settings. A JSON object, so a `null` is sent, not dropped.
     */
    suspend fun updateTaskSettings(changes: JsonObject): TaskSettings {
        return client.put("$baseUrl/api/task-settings/me") {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(changes)
        }.orRefusal().body()
    }

    /** Five real open tasks in the order the user's levels give them, for « Ce qui passe en premier ». */
    suspend fun getTaskOrderPreview(): List<TaskOrderPreviewItem> {
        return client.get("$baseUrl/api/task-settings/me/preview") {
            accept(ContentType.Application.Json)
        }.orRefusal().body<ApiCollection<TaskOrderPreviewItem>>().member
    }

    // Meal settings (MAG-483): the default number of guests. Plain JSON; a refused write (400) says why in `error`.
    suspend fun getMealSettings(): MealSettings {
        return client.get("$baseUrl/api/meal-settings/me") {
            accept(ContentType.Application.Json)
        }.orRefusal().body()
    }

    suspend fun updateMealSettings(values: MealSettingsValues): MealSettings {
        return client.put("$baseUrl/api/meal-settings/me") {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(values)
        }.orRefusal().body()
    }

    // Chat — agent-owned endpoints
    suspend fun getMessages(userId: String = "default", afterDate: String? = null): List<ChatMessage> {
        return client.get("$baseUrl/agent/messages") {
            url.parameters.append("user_id", userId)
            afterDate?.let { url.parameters.append("after", it) }
        }.body()
    }

    suspend fun sendChat(
        message: String,
        screenContext: String? = null,
        userId: String = "default",
        image: ChatImage? = null,
    ): AgentChatResponse {
        return client.post("$baseUrl/agent/chat") {
            waitForAgentReply()
            contentType(ContentType.Application.Json)
            setBody(AgentChatRequest(message = message, user_id = userId, screen_context = screenContext, image = image))
        }.body()
    }

    /** Tells the agent what of an answer was heard or shown before the user cut Maggie off (MAG-223). */
    suspend fun interruptChat(messageId: String?, spokenText: String): ChatMessage {
        return withTimeout(INTERRUPT_TIMEOUT_MS) {
            client.post("$baseUrl/agent/chat/interrupt") {
                contentType(ContentType.Application.Json)
                setBody(InterruptChatRequest(messageId = messageId, spokenText = spokenText))
            }.body()
        }
    }

    suspend fun getMessagesPaginated(
        userId: String = "default",
        beforeId: String? = null,
        limit: Int = 20,
    ): List<ChatMessage> {
        return client.get("$baseUrl/agent/messages") {
            url.parameters.append("user_id", userId)
            url.parameters.append("limit", limit.toString())
            beforeId?.let { url.parameters.append("before", it) }
        }.body()
    }

    suspend fun searchMessages(
        query: String,
        userId: String = "default",
        limit: Int = 20,
    ): List<ChatMessage> {
        return client.get("$baseUrl/agent/messages/search") {
            url.parameters.append("q", query)
            url.parameters.append("user_id", userId)
            url.parameters.append("limit", limit.toString())
        }.body()
    }

    suspend fun getMessageContext(
        messageId: String,
        userId: String = "default",
    ): MessageContextResponse {
        return client.get("$baseUrl/agent/messages/context") {
            url.parameters.append("around", messageId)
            url.parameters.append("user_id", userId)
        }.body()
    }

    // Contexts — agent endpoint
    suspend fun getContexts(includeClosed: Boolean = false): List<Context> {
        return client.get("$baseUrl/agent/contexts") {
            if (includeClosed) url.parameters.append("includeClosed", "true")
        }.body()
    }

    // The client does not `expectSuccess`: a 404 or a 401 must read as a failure, or a
    // deletion that did not happen would look done (MAG-342).
    suspend fun deleteContext(id: String) {
        val response = client.delete("$baseUrl/agent/contexts/$id")
        if (!response.status.isSuccess()) throw IllegalStateException("DELETE context $id: HTTP ${response.status.value}")
    }

    suspend fun deleteMessage(id: String) {
        val response = client.delete("$baseUrl/agent/messages/$id")
        if (!response.status.isSuccess()) throw IllegalStateException("DELETE message $id: HTTP ${response.status.value}")
    }

    /**
     * The owner's thumb on one of Maggie's answers (MAG-23). Rating again replaces it. The
     * answer the agent sends back is not read: the chat already shows what was chosen.
     */
    suspend fun rateMessage(id: String, rating: String, comment: String? = null) {
        val response = client.put("$baseUrl/agent/messages/$id/feedback") {
            contentType(ContentType.Application.Json)
            setBody(MessageFeedbackRequest(rating = rating, comment = comment))
        }
        if (!response.status.isSuccess()) throw IllegalStateException("PUT feedback $id: HTTP ${response.status.value}")
    }

    suspend fun clearMessageFeedback(id: String) {
        val response = client.delete("$baseUrl/agent/messages/$id/feedback")
        if (!response.status.isSuccess()) throw IllegalStateException("DELETE feedback $id: HTTP ${response.status.value}")
    }

    // Chat streaming — AG-UI SSE endpoint
    // [idempotencyKey] is one per message composed: a retry passes the key of the first send,
    // so the agent that already received it answers once (MAG-363).
    fun sendChatStream(
        message: String,
        screenContext: String? = null,
        idempotencyKey: String = java.util.UUID.randomUUID().toString(),
        image: ChatImage? = null,
        replaces: String? = null,
    ): Flow<AgUiEvent> = kotlinx.coroutines.flow.flow {
        try {
            val response = client.post("$baseUrl/agent/chat/stream") {
                waitForAgentStream()
                contentType(ContentType.Application.Json)
                setBody(
                    AgentChatRequest(
                        message = message,
                        screen_context = screenContext,
                        idempotency_key = idempotencyKey,
                        image = image,
                        replaces = replaces,
                    ),
                )
            }
            val status = response.status.value
            if (status != 200) {
                emit(AgUiEvent.Error("HTTP $status"))
            } else {
                val channel = response.bodyAsChannel()
                AgUiStreamParser.parseStream(channel).collect { emit(it) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The question never left the phone: the caller may send it again, nothing was stored.
            if (e is ConnectException || e is UnknownHostException || e is ConnectTimeoutException) throw e
            // Anything later may have reached the agent, which stores the question before it answers.
            emit(AgUiEvent.Error("${e::class.simpleName}: ${e.message ?: "Stream error"}"))
        }
    }

    /**
     * Whisper, with [cleanup] saying whether the model may tidy what it heard.
     *
     * Talking to Maggie asks for none: she reads through a hesitation herself, and the
     * cleanup was a second model call on every sentence (MAG-222). A dictation meant
     * to be written as is passes [TranscriptCleanup.AUTO].
     */
    suspend fun transcribe(audioFile: File, cleanup: TranscriptCleanup = TranscriptCleanup.NONE): String {
        val response = client.submitFormWithBinaryData(
            url = "$baseUrl/agent/transcribe",
            formData = formData {
                append("audio", audioFile.readBytes(), Headers.build {
                    append(HttpHeaders.ContentDisposition, "filename=\"${audioFile.name}\"")
                    append(HttpHeaders.ContentType, audioContentType(audioFile))
                })
                append("cleanup", cleanup.wire)
            },
        )
        if (!response.status.isSuccess()) {
            // FastAPI HTTPException payloads use {"detail": "..."} as JSON;
            // fall back to the raw body when the server returned plain text.
            val body = response.bodyAsText()
            val detail = Regex("\"detail\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
            throw IllegalStateException(detail ?: body.ifBlank { "HTTP ${response.status.value}" })
        }
        val parsed: TranscribeResponse = response.body()
        return parsed.clean.ifBlank { parsed.raw }
    }

    // FCM Token
    // The client does not `expectSuccess`: a refused registration must still read as one,
    // or the device looks registered and no push ever reaches it.
    suspend fun registerFcmToken(token: String, deviceName: String? = null) {
        val response = client.post("$baseUrl/api/fcm_tokens") {
            contentType(ContentType.Application.Json)
            setBody(FcmTokenRequest(token = token, deviceName = deviceName))
        }
        if (!response.status.isSuccess()) throw IllegalStateException("HTTP ${response.status.value}")
    }

    // The token goes in the body, never in the path: a path lands in the access logs.
    suspend fun unregisterFcmToken(token: String) {
        val response = client.delete("$baseUrl/api/fcm_tokens") {
            contentType(ContentType.Application.Json)
            setBody(FcmTokenRequest(token = token))
        }
        if (!response.status.isSuccess()) throw IllegalStateException("HTTP ${response.status.value}")
    }

    // Recipes
    suspend fun getRecipes(): List<Recipe> {
        return client.get("$baseUrl/api/recipes") {
            accept(ContentType("application", "ld+json"))
            withoutPagination()
        }.body<ApiCollection<Recipe>>().member
    }

    suspend fun getRecipe(id: String): Recipe {
        return client.get("$baseUrl/api/recipes/$id") {
            accept(ContentType("application", "ld+json"))
        }.body()
    }

    /** The recipe with its quantities scaled by the API to [guests] people; nothing is stored (MAG-483). */
    suspend fun getScaledRecipe(id: String, guests: Int): Recipe {
        return client.get("$baseUrl/api/recipes/$id") {
            accept(ContentType("application", "ld+json"))
            url.parameters.append("guests", guests.toString())
        }.orRefusal().body()
    }

    // A 400 names the step or the field it refuses (MAG-490): the refusal carries that sentence.
    suspend fun createRecipe(data: JsonObject): Recipe {
        return client.post("$baseUrl/api/recipes") {
            contentType(ContentType.Application.Json)
            accept(ContentType("application", "ld+json"))
            setBody(data)
        }.orRefusal().body()
    }

    suspend fun updateRecipe(id: String, data: JsonObject): Recipe {
        return client.patch("$baseUrl/api/recipes/$id") {
            contentType(MERGE_PATCH)
            accept(ContentType("application", "ld+json"))
            setBody(data)
        }.orRefusal().body()
    }

    /** The user's whole utensil catalogue, by name. */
    suspend fun getUtensils(): List<Utensil> {
        return client.get("$baseUrl/api/utensils") {
            accept(ContentType("application", "ld+json"))
            withoutPagination()
            url.parameters.append("order[name]", "asc")
        }.orRefusal().body<ApiCollection<Utensil>>().member
    }

    suspend fun getRecipeDeletionImpact(id: String): RecipeDeletionImpact {
        return client.get("$baseUrl/api/recipes/$id/deletion-impact") {
            accept(ContentType.Application.Json)
        }.body()
    }

    suspend fun deleteRecipe(id: String) {
        client.delete("$baseUrl/api/recipes/$id")
    }

    // Ingredients
    suspend fun getIngredients(): List<Ingredient> {
        return client.get("$baseUrl/api/ingredients") {
            accept(ContentType("application", "ld+json"))
            withoutPagination()
        }.body<ApiCollection<Ingredient>>().member
    }

    suspend fun createIngredient(request: IngredientCreateRequest): Ingredient {
        return client.post("$baseUrl/api/ingredients") {
            contentType(ContentType.Application.Json)
            accept(ContentType("application", "ld+json"))
            setBody(request)
        }.body()
    }

    // Ciqual Foods
    suspend fun searchCiqualFoods(query: String): List<CiqualFood> {
        return client.get("$baseUrl/ciqual/foods") {
            url.parameters.append("q", query)
            url.parameters.append("limit", "20")
        }.body()
    }

    // Meals
    /** [fromDay] and [toDay] are days (`YYYY-MM-DD`), both ends included. */
    suspend fun getMeals(fromDay: String? = null, toDay: String? = null): List<Meal> {
        return client.get("$baseUrl/api/meals") {
            accept(ContentType("application", "ld+json"))
            withoutPagination()
            url.parameters.append("order[date]", "asc")
            fromDay?.let { url.parameters.append("date[after]", it) }
            toDay?.let { url.parameters.append("date[before]", it) }
        }.body<ApiCollection<Meal>>().member
    }

    suspend fun createMeal(request: MealCreateRequest): Meal {
        return client.post("$baseUrl/api/meals") {
            contentType(ContentType.Application.Json)
            accept(ContentType("application", "ld+json"))
            setBody(request)
        }.orRefusal().body()
    }

    suspend fun updateMeal(id: String, request: MealUpdateRequest): Meal {
        return client.patch("$baseUrl/api/meals/$id") {
            contentType(MERGE_PATCH)
            accept(ContentType("application", "ld+json"))
            setBody(request)
        }.orRefusal().body()
    }

    suspend fun deleteMeal(id: String) {
        client.delete("$baseUrl/api/meals/$id").orRefusal()
    }

    suspend fun getMealGroceryPreview(mealId: String): MealGroceryPreview {
        return client.get("$baseUrl/api/meals/$mealId/grocery_preview") {
            accept(ContentType.Application.Json)
        }.body()
    }

    /** Puts the chosen ingredients of the meal on the list, in packagings; answers the preview as it now stands. */
    suspend fun addMealGroceryItems(mealId: String, request: MealGroceryItemsRequest): MealGroceryPreview {
        return client.post("$baseUrl/api/meals/$mealId/grocery_items") {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(request)
        }.body()
    }

    // Grocery Lists
    suspend fun getGroceryLists(): List<GroceryList> {
        return client.get("$baseUrl/api/grocery_lists") {
            accept(ContentType("application", "ld+json"))
            withoutPagination()
        }.body<ApiCollection<GroceryList>>().member
    }

    suspend fun getGroceryList(id: String): GroceryList {
        return client.get("$baseUrl/api/grocery_lists/$id") {
            accept(ContentType("application", "ld+json"))
        }.body()
    }

    suspend fun patchGroceryItem(itemId: String, checked: Boolean) {
        client.patch("$baseUrl/api/grocery_items/$itemId") {
            contentType(MERGE_PATCH)
            accept(ContentType("application", "ld+json"))
            setBody(buildJsonObject { put("checked", checked) })
        }
    }

    suspend fun deleteGroceryItem(itemId: String) {
        client.delete("$baseUrl/api/grocery_items/$itemId")
    }

    // Past errands (MAG-488)
    /**
     * One page of the past errands, the most recent first (the API's default order). Paged and
     * not read whole: the history only grows, and the screen asks for the next page on demand.
     */
    suspend fun getErrands(page: Int = 1): ApiCollection<Errand> {
        return client.get("$baseUrl/api/errands") {
            accept(ContentType("application", "ld+json"))
            url.parameters.append("page", page.toString())
        }.body()
    }

    suspend fun getErrand(id: String): Errand {
        return client.get("$baseUrl/api/errands/$id") {
            accept(ContentType("application", "ld+json"))
        }.body()
    }

    // Recurring Grocery Items
    suspend fun getRecurringGroceryItems(): List<RecurringGroceryItem> {
        return client.get("$baseUrl/api/recurring_grocery_items") {
            accept(ContentType("application", "ld+json"))
            withoutPagination()
        }.body<ApiCollection<RecurringGroceryItem>>().member
    }

    suspend fun createRecurringGroceryItem(request: RecurringGroceryItemCreateRequest): RecurringGroceryItem {
        return client.post("$baseUrl/api/recurring_grocery_items") {
            contentType(ContentType.Application.Json)
            accept(ContentType("application", "ld+json"))
            setBody(request)
        }.body()
    }

    suspend fun updateRecurringGroceryItemFrequency(id: String, frequency: RecurringFrequency): RecurringGroceryItem {
        return client.patch("$baseUrl/api/recurring_grocery_items/$id") {
            contentType(MERGE_PATCH)
            accept(ContentType("application", "ld+json"))
            setBody(buildJsonObject { put("frequency", frequency.apiValue()) })
        }.body()
    }

    suspend fun deleteRecurringGroceryItem(id: String) {
        client.delete("$baseUrl/api/recurring_grocery_items/$id")
    }

    // Products
    suspend fun getProducts(): List<Product> {
        return client.get("$baseUrl/api/products") {
            accept(ContentType("application", "ld+json"))
            withoutPagination()
        }.body<ApiCollection<Product>>().member
    }

    suspend fun createProduct(request: ProductCreateRequest): Product {
        return client.post("$baseUrl/api/products") {
            contentType(ContentType.Application.Json)
            accept(ContentType("application", "ld+json"))
            setBody(request)
        }.body()
    }

    /** The months a product is in season (MAG-494); an empty list is no season. */
    suspend fun updateProductSeasonMonths(id: String, months: List<Int>): Product {
        return client.patch("$baseUrl/api/products/$id") {
            contentType(MERGE_PATCH)
            accept(ContentType("application", "ld+json"))
            setBody(buildJsonObject { put("seasonMonths", buildJsonArray { months.forEach { add(JsonPrimitive(it)) } }) })
        }.body()
    }

    suspend fun deleteProduct(id: String) {
        client.delete("$baseUrl/api/products/$id")
    }

    suspend fun getProduct(id: String): Product {
        return client.get("$baseUrl/api/products/$id") {
            accept(ContentType("application", "ld+json"))
        }.orRefusal().body()
    }

    /** Replaces the product's whole aisle list: a store left out has no aisle any more (MAG-493). */
    suspend fun updateProductAisles(id: String, aisles: List<ProductAisle>): Product {
        return client.patch("$baseUrl/api/products/$id") {
            contentType(MERGE_PATCH)
            accept(ContentType("application", "ld+json"))
            setBody(ProductAislesRequest(aisles))
        }.orRefusal().body()
    }

    // Stores
    suspend fun getStores(): List<Store> {
        return client.get("$baseUrl/api/stores") {
            accept(ContentType("application", "ld+json"))
            withoutPagination()
        }.body<ApiCollection<Store>>().member
    }

    suspend fun createStore(request: StoreCreateRequest): Store {
        return client.post("$baseUrl/api/stores") {
            contentType(ContentType.Application.Json)
            accept(ContentType("application", "ld+json"))
            setBody(request)
        }.body()
    }

    suspend fun deleteStore(id: String) {
        client.delete("$baseUrl/api/stores/$id")
    }

    // Aisles of a store, in the order of the way through it (MAG-493). Not paginated.
    suspend fun getAisles(storeId: String): List<Aisle> {
        return client.get("$baseUrl/api/aisles") {
            accept(ContentType("application", "ld+json"))
            url.parameters.append("store", "/api/stores/$storeId")
        }.orRefusal().body<ApiCollection<Aisle>>().member
    }

    suspend fun createAisle(request: AisleCreateRequest): Aisle {
        return client.post("$baseUrl/api/aisles") {
            contentType(ContentType.Application.Json)
            accept(ContentType("application", "ld+json"))
            setBody(request)
        }.orRefusal().body()
    }

    /** `{name}` renames it; `{position: n}` moves it to place n (1 = first), the others renumbered around it. */
    suspend fun updateAisle(id: String, data: JsonObject): Aisle {
        return client.patch("$baseUrl/api/aisles/$id") {
            contentType(MERGE_PATCH)
            accept(ContentType("application", "ld+json"))
            setBody(data)
        }.orRefusal().body()
    }

    suspend fun deleteAisle(id: String) {
        client.delete("$baseUrl/api/aisles/$id").orRefusal()
    }

    // Finance — Accounts
    suspend fun getAccounts(): List<Account> {
        return client.get("$baseUrl/api/accounts") {
            accept(ContentType("application", "ld+json"))
            withoutPagination()
        }.body<ApiCollection<Account>>().member
    }

    suspend fun createAccount(request: AccountCreateRequest): Account {
        return client.post("$baseUrl/api/accounts") {
            contentType(ContentType.Application.Json)
            accept(ContentType("application", "ld+json"))
            setBody(request)
        }.body()
    }

    suspend fun deleteAccount(id: String) {
        client.delete("$baseUrl/api/accounts/$id")
    }

    // Finance — Categories
    suspend fun getCategories(): List<Category> {
        return client.get("$baseUrl/api/categories") {
            accept(ContentType("application", "ld+json"))
            withoutPagination()
        }.body<ApiCollection<Category>>().member
    }

    // The client does not `expectSuccess`: a 422 body would fail to decode as a Category
    // and the reason it carries would be lost.
    private suspend fun HttpResponse.orRefusal(): HttpResponse {
        if (!status.isSuccess()) throw ApiRefusalException(status.value, refusalMessage(status.value, bodyAsText()))
        return this
    }

    suspend fun createCategory(request: CategoryCreateRequest): Category {
        return client.post("$baseUrl/api/categories") {
            contentType(ContentType.Application.Json)
            accept(ContentType("application", "ld+json"))
            setBody(request)
        }.orRefusal().body()
    }

    suspend fun updateCategory(id: String, data: JsonObject): Category {
        return client.patch("$baseUrl/api/categories/$id") {
            contentType(MERGE_PATCH)
            accept(ContentType("application", "ld+json"))
            setBody(data)
        }.orRefusal().body()
    }

    suspend fun deleteCategory(id: String) {
        client.delete("$baseUrl/api/categories/$id")
    }

    // One row asked for: the page is not wanted, only the collection's total.
    suspend fun countTransactionsOfCategory(categoryId: String): Int {
        val page = client.get("$baseUrl/api/transactions") {
            accept(ContentType("application", "ld+json"))
            url.parameters.append("category", "/api/categories/$categoryId")
            url.parameters.append("itemsPerPage", "1")
        }.body<ApiCollection<Transaction>>()
        return page.totalItems ?: page.member.size
    }

    /** How many transactions [parameter] (`qualifiedBySeries` or `qualifiedByRule`) points at [iri]. */
    suspend fun countTransactionsQualifiedBy(parameter: String, iri: String): Int {
        val page = client.get("$baseUrl/api/transactions") {
            accept(ContentType("application", "ld+json"))
            url.parameters.append(parameter, iri)
            url.parameters.append("itemsPerPage", "1")
        }.body<ApiCollection<Transaction>>()
        return page.totalItems ?: page.member.size
    }

    // Finance — Transactions
    suspend fun getTransactions(accountId: String? = null): List<Transaction> {
        return client.get("$baseUrl/api/transactions") {
            accept(ContentType("application", "ld+json"))
            url.parameters.append("order[bookedAt]", "desc")
            // "account", not "accountId": API Platform names a relation
            // filter after the property, and takes the IRI the provider hands
            // out. "accountId" was declared nowhere, so it was dropped and
            // every account showed the full transaction list.
            accountId?.let { url.parameters.append("account", "/api/accounts/$it") }
        }.body<ApiCollection<Transaction>>().member
    }

    /** One page (100 lines at most) of a category's own lines, newest first: sub-categories are not included. */
    suspend fun getTransactionsOfCategoryPage(categoryId: String, page: Int): List<Transaction> {
        return client.get("$baseUrl/api/transactions") {
            accept(ContentType("application", "ld+json"))
            url.parameters.append("category", "/api/categories/$categoryId")
            url.parameters.append("order[bookedAt]", "desc")
            url.parameters.append("itemsPerPage", "100")
            url.parameters.append("page", page.toString())
        }.body<ApiCollection<Transaction>>().member
    }

    /**
     * Every line a series recognised, newest first. The API caps a page at 100, so the pages are
     * read until the collection's total is reached: a long series shows all of its lines (MAG-438).
     */
    suspend fun getTransactionsOfRecurringOperation(seriesId: String): List<Transaction> {
        val lines = mutableListOf<Transaction>()
        var page = 1
        while (true) {
            val collection = client.get("$baseUrl/api/transactions") {
                accept(ContentType("application", "ld+json"))
                url.parameters.append("recurringOperation", "/api/recurring_operations/$seriesId")
                url.parameters.append("order[bookedAt]", "desc")
                url.parameters.append("itemsPerPage", "100")
                url.parameters.append("page", page.toString())
            }.body<ApiCollection<Transaction>>()
            lines += collection.member
            if (collection.member.isEmpty() || lines.size >= (collection.totalItems ?: lines.size)) return lines
            page++
        }
    }

    /**
     * The account's rejected payments, one line each; the transaction list leaves them out (MAG-375).
     * The pile, or with [settled] those the owner already settled (MAG-424).
     */
    suspend fun getAccountIncidents(accountId: String, settled: Boolean = false): List<AccountIncident> {
        return client.get("$baseUrl/api/accounts/$accountId/incidents") {
            if (settled) url.parameters.append("settled", "true")
        }.orRefusal().body<AccountIncidents>().incidents
    }

    /** « Régularisé »: either leg of the rejection names it, the API settles both (MAG-424). */
    suspend fun settleRejection(transactionId: String) {
        client.post("$baseUrl/api/finance/transactions/$transactionId/rejection-settlement").orRefusal()
    }

    /** « Annuler »: the rejection goes back to the pile. */
    suspend fun unsettleRejection(transactionId: String) {
        client.delete("$baseUrl/api/finance/transactions/$transactionId/rejection-settlement").orRefusal()
    }

    suspend fun createTransaction(request: TransactionCreateRequest): Transaction {
        return client.post("$baseUrl/api/transactions") {
            contentType(ContentType.Application.Json)
            accept(ContentType("application", "ld+json"))
            setBody(request)
        }.orRefusal().body()
    }

    suspend fun deleteTransaction(id: String) {
        client.delete("$baseUrl/api/transactions/$id")
    }

    suspend fun getTransactionTransfer(id: String): TransferInfo {
        return client.get("$baseUrl/api/finance/transactions/$id/transfer").body()
    }

    suspend fun getTransferCandidates(id: String): List<TransferLeg> {
        return client.get("$baseUrl/api/finance/transactions/$id/transfer-candidates")
            .body<TransferCandidates>().candidates
    }

    /** Marks (counterpartId optional) or releases a line; the API answers with the new state. */
    suspend fun setTransactionTransfer(id: String, request: TransferUpdateRequest): TransferInfo {
        return client.put("$baseUrl/api/finance/transactions/$id/transfer") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()
    }

    // Finance — The « À qualifier » pile
    suspend fun getTransactionsToSort(limit: Int? = null, oldestFirst: Boolean = false): ToSortPile {
        return client.get("$baseUrl/api/finance/transactions/to-sort") {
            limit?.let { url.parameters.append("limit", it.toString()) }
            if (oldestFirst) url.parameters.append("order", "oldest")
        }.body()
    }

    suspend fun sortTransaction(id: String, request: SortRequest): SortResult {
        return client.post("$baseUrl/api/finance/transactions/$id/sort") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()
    }

    // Finance — Contacts and the labels they appear under (MAG-450)
    suspend fun getContacts(): List<Contact> {
        return client.get("$baseUrl/api/contacts") {
            accept(ContentType("application", "ld+json"))
            withoutPagination()
        }.orRefusal().body<ApiCollection<Contact>>().member
    }

    suspend fun createContact(request: ContactCreateRequest): Contact {
        return client.post("$baseUrl/api/contacts") {
            contentType(ContentType.Application.Json)
            accept(ContentType("application", "ld+json"))
            setBody(request)
        }.orRefusal().body()
    }

    /** [notes] null erases them: the merge-patch spells the null out, absent would mean « unchanged ». */
    suspend fun updateContact(id: String, name: String, notes: String?): Contact {
        return client.patch("$baseUrl/api/contacts/$id") {
            contentType(MERGE_PATCH)
            accept(ContentType("application", "ld+json"))
            setBody(
                buildJsonObject {
                    put("name", JsonPrimitive(name))
                    put("notes", notes?.let(::JsonPrimitive) ?: JsonNull)
                },
            )
        }.orRefusal().body()
    }

    /** The contact's lines stay, detached; its labels go with it. */
    suspend fun deleteContact(id: String) {
        client.delete("$baseUrl/api/contacts/$id").orRefusal()
    }

    suspend fun getContactCounterparties(contactId: String): List<ContactCounterparty> {
        return client.get("$baseUrl/api/contact_counterparties") {
            accept(ContentType("application", "ld+json"))
            withoutPagination()
            url.parameters.append("contact", contactId)
        }.orRefusal().body<ApiCollection<ContactCounterparty>>().member
    }

    /** A 400 says the label already belongs to someone else, by name: [ApiRefusalException] carries the sentence. */
    suspend fun createContactCounterparty(request: ContactCounterpartyCreateRequest): ContactCounterparty {
        return client.post("$baseUrl/api/contact_counterparties") {
            contentType(ContentType.Application.Json)
            accept(ContentType("application", "ld+json"))
            setBody(request)
        }.orRefusal().body()
    }

    /** Detaches the lines the label had attached. */
    suspend fun deleteContactCounterparty(id: String) {
        client.delete("$baseUrl/api/contact_counterparties/$id").orRefusal()
    }

    suspend fun getCounterpartySuggestions(contactId: String): List<CounterpartySuggestion> {
        return client.get("$baseUrl/api/finance/contacts/$contactId/counterparty-suggestions").orRefusal().body()
    }

    // Finance — My part of a shared spend and the créances it makes (MAG-451)
    suspend fun getMyShare(transactionId: String): MyShare {
        return client.get("$baseUrl/api/finance/transactions/$transactionId/my-share").orRefusal().body()
    }

    /** A 400 carries the server's sentence (shares that do not add up, a part too large…) in [ApiRefusalException]. */
    suspend fun setMyShare(transactionId: String, request: MyShareRequest): MyShare {
        return client.put("$baseUrl/api/finance/transactions/$transactionId/my-share") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.orRefusal().body()
    }

    /** The whole spend is the owner's again; refused (400) once someone gave something back. */
    suspend fun removeMyShare(transactionId: String): MyShare {
        return client.delete("$baseUrl/api/finance/transactions/$transactionId/my-share").orRefusal().body()
    }

    // Finance — A received transfer declared « À rembourser » (MAG-452)
    suspend fun getTransactionDebt(transactionId: String): TransactionDebt {
        return client.get("$baseUrl/api/finance/transactions/$transactionId/debt").orRefusal().body()
    }

    /** 201 or 200, the same body; a 400 carries the server's sentence in [ApiRefusalException]. */
    suspend fun recordTransactionDebt(transactionId: String, request: TransactionDebtRequest): TransactionDebt {
        return client.post("$baseUrl/api/finance/transactions/$transactionId/debt") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.orRefusal().body()
    }

    /** The transfer is a recette again; refused (400) once something was given back. */
    suspend fun removeTransactionDebt(transactionId: String): TransactionDebt {
        return client.delete("$baseUrl/api/finance/transactions/$transactionId/debt").orRefusal().body()
    }

    suspend fun getPeerDebts(): List<PeerDebt> {
        return client.get("$baseUrl/api/peer_debts") {
            accept(ContentType("application", "ld+json"))
            withoutPagination()
        }.orRefusal().body<ApiCollection<PeerDebt>>().member
    }

    suspend fun getPeerDebtSummary(): PeerDebtSummary {
        return client.get("$baseUrl/api/finance/peer-debts/summary").orRefusal().body()
    }

    // Finance — Envelopes (budgets)
    suspend fun getEnvelopes(): List<Envelope> {
        return client.get("$baseUrl/api/envelopes") {
            accept(ContentType("application", "ld+json"))
            withoutPagination()
        }.body<ApiCollection<Envelope>>().member
    }

    suspend fun getEnvelope(id: String): Envelope {
        return client.get("$baseUrl/api/envelopes/$id") {
            accept(ContentType("application", "ld+json"))
        }.orRefusal().body()
    }

    suspend fun createEnvelope(request: EnvelopeCreateRequest): Envelope {
        return client.post("$baseUrl/api/envelopes") {
            contentType(ContentType.Application.Json)
            accept(ContentType("application", "ld+json"))
            setBody(request)
        }.orRefusal().body()
    }

    suspend fun createOneOffEnvelope(request: OneOffEnvelopeCreateRequest): Envelope {
        return client.post("$baseUrl/api/envelopes") {
            contentType(ContentType.Application.Json)
            accept(ContentType("application", "ld+json"))
            setBody(request)
        }.orRefusal().body()
    }

    /** Writes the envelope's amount, mode, month, seasons and threshold in one go (the tablet's editor). */
    suspend fun updateEnvelope(id: String, request: EnvelopeUpdateRequest): Envelope {
        return client.patch("$baseUrl/api/envelopes/$id") {
            contentType(MERGE_PATCH)
            accept(ContentType("application", "ld+json"))
            setBody(request.toJson())
        }.orRefusal().body()
    }

    /** Sets the envelope's alert threshold; null puts it back to the default. */
    suspend fun updateEnvelopeAlertThreshold(id: String, percent: Int?): Envelope {
        return client.patch("$baseUrl/api/envelopes/$id") {
            contentType(MERGE_PATCH)
            accept(ContentType("application", "ld+json"))
            setBody(buildJsonObject { put("alertThresholdPercent", percent?.let(::JsonPrimitive) ?: JsonNull) })
        }.orRefusal().body()
    }

    suspend fun deleteEnvelope(id: String) {
        client.delete("$baseUrl/api/envelopes/$id").orRefusal()
    }

    /** Shares a mother's amount between her daughters in one go; a 400 means nothing was changed. */
    suspend fun splitEnvelope(id: String, request: EnvelopeSplitRequest) {
        client.put("$baseUrl/api/finance/envelopes/$id/split") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.orRefusal()
    }

    suspend fun getBudgetStatus(year: Int, month: Int): BudgetStatus {
        return client.get("$baseUrl/api/finance/budget-status") {
            url.parameters.append("year", year.toString())
            url.parameters.append("month", month.toString())
        }.body()
    }

    suspend fun rollOverEnvelopes(request: RollOverRequest): RollOverResult {
        return client.post("$baseUrl/api/finance/rollover-envelopes") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()
    }

    /** The monthly envelopes of [month] (YYYY-MM) against their actuals, and the amounts proposed for the next one (MAG-461). */
    suspend fun getEnvelopeAdjustments(month: String): EnvelopeAdjustments {
        return client.get("$baseUrl/api/finance/envelope-adjustments") {
            url.parameters.append("month", month)
        }.orRefusal().body()
    }

    /** Sets the amounts of the month after [EnvelopeAdjustmentsRequest.month]; a 400 means nothing was written. */
    suspend fun applyEnvelopeAdjustments(request: EnvelopeAdjustmentsRequest): EnvelopeAdjustmentsResult {
        return client.post("$baseUrl/api/finance/envelope-adjustments") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.orRefusal().body()
    }

    // Finance — Loans
    suspend fun getLoans(): List<Loan> {
        return client.get("$baseUrl/api/loans") {
            accept(ContentType("application", "ld+json"))
            withoutPagination()
        }.body<ApiCollection<Loan>>().member
    }

    suspend fun createLoan(request: LoanCreateRequest): Loan {
        return client.post("$baseUrl/api/loans") {
            contentType(ContentType.Application.Json)
            accept(ContentType("application", "ld+json"))
            setBody(request)
        }.body()
    }

    suspend fun deleteLoan(id: String) {
        client.delete("$baseUrl/api/loans/$id")
    }

    suspend fun getDebtTimeline(months: Int): DebtTimeline {
        return client.get("$baseUrl/api/finance/debt-timeline") {
            url.parameters.append("months", months.toString())
        }.body()
    }

    // Finance — Dashboard
    suspend fun getFinanceDashboard(year: Int, month: Int): FinanceDashboard {
        return client.get("$baseUrl/api/finance/dashboard") {
            url.parameters.append("year", year.toString())
            url.parameters.append("month", month.toString())
        }.body()
    }

    // Finance — Upcoming (the week and the month after)
    suspend fun getUpcomingProjection(): UpcomingProjection {
        return client.get("$baseUrl/api/finance/upcoming").body()
    }

    // Finance — Month balance
    suspend fun getMonthBalance(month: String): MonthBalance {
        return client.get("$baseUrl/api/finance/month-balance") {
            url.parameters.append("month", month)
        }.body()
    }

    // Finance — Monthly review
    suspend fun getMonthlyReview(year: Int, month: Int): MonthlyReview {
        return client.get("$baseUrl/api/finance/monthly-review") {
            url.parameters.append("year", year.toString())
            url.parameters.append("month", month.toString())
        }.body()
    }

    /**
     * Qualifies the transaction by hand: its category ([categoryIri], null takes it away) and its
     * qualification, always together — the API refuses a half qualification.
     */
    suspend fun updateTransactionQualification(id: String, categoryIri: String?, qualification: Qualification): Transaction {
        return client.patch("$baseUrl/api/transactions/$id") {
            contentType(MERGE_PATCH)
            accept(ContentType("application", "ld+json"))
            setBody(qualificationPatch(categoryIri, qualification))
        }.orRefusal().body()
    }

    /** Attributes the spend to the one-off envelope [envelopeIri], or takes the attribution back with null. */
    suspend fun updateTransactionOneOffEnvelope(id: String, envelopeIri: String?): Transaction {
        return client.patch("$baseUrl/api/transactions/$id") {
            contentType(MERGE_PATCH)
            accept(ContentType("application", "ld+json"))
            setBody(buildJsonObject { put("oneOffEnvelope", envelopeIri?.let(::JsonPrimitive) ?: JsonNull) })
        }.orRefusal().body()
    }

    /** Writes the transaction's note; null (or blank) erases it. */
    suspend fun updateTransactionNote(id: String, note: String?): Transaction {
        return client.patch("$baseUrl/api/transactions/$id") {
            contentType(MERGE_PATCH)
            accept(ContentType("application", "ld+json"))
            setBody(buildJsonObject { put("note", note?.let(::JsonPrimitive) ?: JsonNull) })
        }.orRefusal().body()
    }

    suspend fun rateTransaction(id: String, verdict: String) {
        client.patch("$baseUrl/api/transactions/$id") {
            contentType(ContentType("application", "merge-patch+json"))
            setBody(RetrospectRequest(retrospect = verdict))
        }.orRefusal()
    }

    // Finance — Categorization rules
    suspend fun getCategorizationRules(): List<CategorizationRule> {
        return client.get("$baseUrl/api/categorization_rules") {
            accept(ContentType("application", "ld+json"))
            withoutPagination()
        }.body<ApiCollection<CategorizationRule>>().member
    }

    /**
     * How many rules file under any of [categoryIds], or `null` when the first page does
     * not hold them all — a wrong number in a deletion's warning is worse than none.
     */
    suspend fun countCategorizationRulesOf(categoryIds: Set<String>): Int? {
        val page = client.get("$baseUrl/api/categorization_rules") {
            accept(ContentType("application", "ld+json"))
        }.body<ApiCollection<CategorizationRule>>()
        if ((page.totalItems ?: page.member.size) > page.member.size) return null
        return page.member.count { rule -> categoryIds.any(rule::targetsCategory) }
    }

    suspend fun createCategorizationRule(request: CategorizationRuleCreateRequest): CategorizationRule {
        return client.post("$baseUrl/api/categorization_rules") {
            contentType(ContentType.Application.Json)
            accept(ContentType("application", "ld+json"))
            setBody(request)
        }.body()
    }

    /** What a rule still being written would catch; nothing is stored. */
    suspend fun previewCategorizationRule(request: RulePreviewRequest): RulePreview {
        return client.post("$baseUrl/api/finance/categorization-rules/preview") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.orRefusal().body()
    }

    suspend fun deleteCategorizationRule(id: String) {
        client.delete("$baseUrl/api/categorization_rules/$id")
    }

    suspend fun applyCategorizationRules(): ApplyRulesResult {
        return client.post("$baseUrl/api/finance/apply-categorization-rules").body()
    }

    // Finance — Rules the statement implies
    suspend fun getCategorizationRuleSuggestions(): List<RuleSuggestion> {
        return client.get("$baseUrl/api/finance/categorization-rules/suggestions")
            .body<RuleSuggestionsResponse>().suggestions
    }

    suspend fun acceptCategorizationRuleSuggestions(
        request: AcceptRuleSuggestionsRequest,
    ): AcceptRuleSuggestionsResult {
        return client.post("$baseUrl/api/finance/categorization-rules/suggestions") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()
    }

    // Finance — Recurring operations
    suspend fun getRecurringOperationsOverview(): RecurringOperationsOverview {
        return client.get("$baseUrl/api/finance/recurring-operations/overview").body()
    }

    suspend fun getRecurringOperationSuggestions(): List<RecurringSuggestion> {
        return client.get("$baseUrl/api/finance/recurring-operations/suggestions")
            .body<RecurringSuggestionsResponse>().suggestions
    }

    suspend fun acceptRecurringOperationSuggestions(
        suggestions: List<RecurringSuggestion>,
    ): AcceptRecurringSuggestionsResult {
        return client.post("$baseUrl/api/finance/recurring-operations/suggestions/accept") {
            contentType(ContentType.Application.Json)
            setBody(AcceptRecurringSuggestionsRequest(suggestions))
        }.body()
    }

    /** The catch-up over the history; with `dryRun` it only says what it would attach. */
    suspend fun attachRecurringOperations(dryRun: Boolean): RecurringAttachReport {
        return client.post("$baseUrl/api/finance/recurring-operations/attach") {
            contentType(ContentType.Application.Json)
            setBody(RecurringAttachRequest(dryRun = dryRun))
        }.body()
    }

    /** What a series still being written would recognise; nothing is stored. */
    suspend fun previewRecurringOperation(request: RecurringOperationPreviewRequest): RecurringOperationPreview {
        return client.post("$baseUrl/api/finance/recurring-operations/preview") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.orRefusal().body()
    }

    suspend fun getRecurringOperationRecords(): List<RecurringOperationRecord> {
        return client.get("$baseUrl/api/recurring_operations") {
            accept(ContentType("application", "ld+json"))
            withoutPagination()
        }.body<ApiCollection<RecurringOperationRecord>>().member
    }

    suspend fun createRecurringOperation(request: RecurringOperationWriteRequest): RecurringOperationRecord {
        return client.post("$baseUrl/api/recurring_operations") {
            contentType(ContentType.Application.Json)
            accept(ContentType("application", "ld+json"))
            setBody(request)
        }.body()
    }

    suspend fun updateRecurringOperation(id: String, request: RecurringOperationWriteRequest): RecurringOperationRecord {
        return client.patch("$baseUrl/api/recurring_operations/$id") {
            contentType(MERGE_PATCH)
            accept(ContentType("application", "ld+json"))
            setBody(request)
        }.body()
    }

    suspend fun deleteRecurringOperation(id: String) {
        client.delete("$baseUrl/api/recurring_operations/$id")
    }

    suspend fun setTransactionRecurrence(transactionId: String, seriesId: String?, occurrenceOn: String?) {
        client.patch("$baseUrl/api/transactions/$transactionId") {
            contentType(MERGE_PATCH)
            setBody(
                TransactionRecurrenceRequest(
                    recurringOperation = seriesId?.let(::recurringOperationIri),
                    recurringOccurrenceOn = occurrenceOn,
                ),
            )
        }
    }

    // Finance — Bank connections
    suspend fun getBankConnections(): List<BankConnection> {
        return client.get("$baseUrl/api/finance/bank-connections")
            .body<BankConnectionsResponse>().connections
    }

    /**
     * Pulls what the banks have. Triggered by hand rather than on a timer:
     * every fetch spends part of the bank's daily allowance.
     */
    suspend fun syncBankConnections(): BankSyncResult {
        return client.post("$baseUrl/api/finance/bank-connections/sync").body()
    }

    /** Hands back the URL to send the user to, at their bank. */
    suspend fun reconnectBankConnection(id: String): BankAuthorization {
        return client.post("$baseUrl/api/finance/bank-connections/$id/reconnect").body()
    }

    // Finance — Safety cushion
    suspend fun getCushionStatus(): CushionStatus {
        return client.get("$baseUrl/api/finance/cushion-status").body()
    }

    suspend fun configureCushion(request: CushionConfigRequest): CushionStatus {
        return client.patch("$baseUrl/api/finance/cushion-config") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()
    }

    // Finance — Treasury until the salary comes
    suspend fun getTreasuryOutlook(): TreasuryOutlook {
        return client.get("$baseUrl/api/finance/treasury-outlook").orRefusal().body()
    }

    suspend fun updateTreasurySettings(request: TreasurySettingsRequest): TreasurySettings {
        return client.patch("$baseUrl/api/finance/treasury-settings") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.orRefusal().body()
    }

    // Finance — Savings advances
    suspend fun getSavingsAdvances(): SavingsAdvances {
        return client.get("$baseUrl/api/finance/savings-advances").orRefusal().body()
    }

    suspend fun setSalaryExpectedDay(day: Int?): TreasurySettingsResult {
        return client.patch("$baseUrl/api/finance/treasury-settings") {
            contentType(ContentType.Application.Json)
            setBody(SalaryExpectedDayRequest(day))
        }.orRefusal().body()
    }

    // Grocery Add Item
    suspend fun addGroceryItem(request: AddGroceryItemRequest): AddGroceryItemResponse {
        return client.post("$baseUrl/api/grocery/add-item") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()
    }

    /** The products due again by their purchase rhythm (MAG-495): a plain JSON array. */
    suspend fun getGrocerySuggestions(): List<PurchaseSuggestion> {
        return client.get("$baseUrl/api/grocery-suggestions") {
            accept(ContentType.Application.Json)
        }.orRefusal().body()
    }

    /**
     * « Pas besoin »: the product leaves the suggestions until its next due date.
     * The client does not `expectSuccess`, so a refusal is checked here or it would look done.
     */
    suspend fun snoozeGrocerySuggestion(productId: String) {
        client.post("$baseUrl/api/grocery-suggestions/$productId/snooze") {
            accept(ContentType.Application.Json)
        }.orRefusal()
    }

    // Grocery Reorder Items
    suspend fun reorderGroceryItems(request: ReorderGroceryItemsRequest) {
        client.patch("$baseUrl/api/grocery/reorder") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
    }

    suspend fun editGroceryItem(itemId: String, request: EditGroceryItemRequest) {
        client.patch("$baseUrl/api/grocery/edit-item/$itemId") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
    }

    suspend fun endErrand(request: EndErrandRequest): EndErrandResponse {
        return client.post("$baseUrl/api/grocery/end-errand") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()
    }

    // Notifications
    suspend fun getNotifications(unreadOnly: Boolean = false): List<Notification> {
        return client.get("$baseUrl/api/notifications") {
            accept(ContentType("application", "ld+json"))
            withoutPagination()
            if (unreadOnly) {
                url.parameters.append("exists[readAt]", "false")
            }
        }.body<ApiCollection<Notification>>().member
    }

    suspend fun markNotificationRead(id: String): Notification {
        return client.patch("$baseUrl/api/notifications/$id") {
            contentType(MERGE_PATCH)
            accept(ContentType("application", "ld+json"))
            setBody(buildJsonObject {
                put("readAt", java.time.Instant.now().toString())
            })
        }.body()
    }

    suspend fun deleteNotification(id: String) {
        client.delete("$baseUrl/api/notifications/$id")
    }

    // Search
    suspend fun search(query: String, page: Int = 1, limit: Int = 10, types: String? = null): SearchResponse {
        return client.get("$baseUrl/api/search") {
            accept(ContentType.Application.Json)
            url.parameters.append("q", query)
            url.parameters.append("page", page.toString())
            url.parameters.append("limit", limit.toString())
            types?.let { url.parameters.append("types", it) }
        }.body()
    }

    // Proactions — Maggie's runs, events of her own agenda (MAG-328)
    /** [from] and [to] are ISO-8601 instants with their offset, both ends included. */
    suspend fun getProactions(from: String? = null, to: String? = null): List<Proaction> {
        return client.get("$baseUrl/api/proactions") {
            accept(ContentType("application", "ld+json"))
            withoutPagination()
            url.parameters.append("order[startAt]", "asc")
            from?.let { url.parameters.append("startAt[after]", it) }
            to?.let { url.parameters.append("startAt[before]", it) }
        }.body<ApiCollection<Proaction>>().member
    }

    // Approvals — agent endpoints
    suspend fun getPendingApprovals(): List<PendingApproval> {
        return client.get("$baseUrl/agent/approvals") {
            url.parameters.append("status", "pending")
        }.body()
    }

    // `channel` (chat, notification or voice) is where the answer was given: it is written in the line the thread keeps (MAG-336).
    suspend fun approve(id: String, channel: String = "chat"): PendingApproval = decideApproval(id, "approve", channel)

    suspend fun deny(id: String, channel: String = "chat"): PendingApproval = decideApproval(id, "deny", channel)

    // The client does not `expectSuccess`: without this check a 409 body ({"detail": …})
    // would fail to decode and read as a network error, hiding that the answer is final.
    private suspend fun decideApproval(id: String, verb: String, channel: String): PendingApproval {
        val response = client.post("$baseUrl/agent/approvals/$id/$verb") {
            url.parameters.append("channel", channel)
        }
        if (!response.status.isSuccess()) throw ApprovalDecisionException(response.status.value)
        return response.body()
    }

    // TTS — agent endpoint
    suspend fun getTtsVoices(): List<TtsVoice> {
        return client.get("$baseUrl/agent/tts/voices").body()
    }

    suspend fun getTtsVoice(): String {
        val response: TtsVoiceResponse = client.get("$baseUrl/agent/tts/voice").body()
        return response.voice
    }

    /** The name the user gave Maggie (personality `name`); blank when unset. */
    suspend fun getAgentName(): String {
        val response: PersonalityResponse = client.get("$baseUrl/agent/personality").body()
        return response.name
    }

    suspend fun setTtsVoice(voice: String) {
        client.put("$baseUrl/agent/tts/voice") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("voice", voice) })
        }
    }

    suspend fun synthesizeSpeech(text: String, voice: String): ByteArray {
        val response = client.post("$baseUrl/agent/tts/synthesize") {
            contentType(ContentType.Application.Json)
            setBody(TtsSynthesizeRequest(text = text, voice = voice))
        }
        return response.bodyAsChannel().readRemaining().readByteArray()
    }

    /**
     * The type Whisper is told the clip is, read off the name rather than hardcoded:
     * the microphone now writes WAV, since raw PCM is the only thing the phone's own
     * engine can be fed (MAG-222), and a stale `audio/mp4` would mislabel it.
     */
    internal fun audioContentType(audioFile: File): String = when (audioFile.extension.lowercase()) {
        "wav" -> "audio/wav"
        "webm" -> "audio/webm"
        else -> "audio/mp4"
    }
}

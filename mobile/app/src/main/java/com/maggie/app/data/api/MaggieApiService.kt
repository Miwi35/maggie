package com.maggie.app.data.api

import com.maggie.app.BuildConfig
import com.maggie.app.data.model.Agenda
import com.maggie.app.data.model.AgUiEvent
import com.maggie.app.data.model.ChatMessage
import com.maggie.app.data.model.Context
import com.maggie.app.data.model.CiqualFood
import com.maggie.app.data.model.Event
import com.maggie.app.data.model.GoogleCalendar
import com.maggie.app.data.model.GroceryList
import com.maggie.app.data.model.Ingredient
import com.maggie.app.data.model.Meal
import com.maggie.app.data.model.Notification
import com.maggie.app.data.model.Proaction
import com.maggie.app.data.model.Product
import com.maggie.app.data.model.Recipe
import com.maggie.app.data.model.Account
import com.maggie.app.data.model.ApplyRulesResult
import com.maggie.app.data.model.BudgetStatus
import com.maggie.app.data.model.CategorizationRule
import com.maggie.app.data.model.CushionStatus
import com.maggie.app.data.model.DailyScore
import com.maggie.app.data.model.Category
import com.maggie.app.data.model.Store
import com.maggie.app.data.model.Envelope
import com.maggie.app.data.model.RollOverResult
import com.maggie.app.data.model.Transaction
import com.maggie.app.data.model.TtsVoice
import com.maggie.app.data.model.RecurringGroceryItem
import com.maggie.app.data.model.SearchResponse
import com.maggie.app.data.model.Task
import com.maggie.app.data.model.User
import com.maggie.app.data.model.UserPreference
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.accept
import io.ktor.client.request.delete
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

@Serializable
data class ApiCollection<T>(val member: List<T> = emptyList())

@Serializable
data class EventCreateRequest(
    val summary: String,
    val startAt: String,
    val endAt: String,
    val allDay: Boolean = false,
    val description: String? = null,
    val location: String? = null,
    val timeZone: String = "Europe/Paris",
    val agenda: String? = null,
    val rrule: String? = null,
    val recurringEvent: String? = null,
    val originalStartAt: String? = null,
    val status: String = "confirmed",
)

@Serializable
data class TaskCreateRequest(
    val title: String,
    val description: String? = null,
    val priority: String = "medium",
    val criticality: String = "low",
    val dueDate: String? = null,
)

@Serializable
data class AgendaCreateRequest(
    val name: String,
    val color: String? = null,
    val description: String? = null,
)

@Serializable
data class GoogleCalendarImportRequest(
    val googleCalendarId: String,
    val name: String? = null,
    val color: String? = null,
)

@Serializable
data class MessageContextResponse(val messages: List<ChatMessage> = emptyList())

@Serializable
data class AgentChatRequest(
    val message: String,
    val user_id: String = "default",
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
data class FcmTokenRequest(
    val token: String,
    val deviceName: String? = null,
)

@Serializable
data class RecipeCreateRequest(
    val name: String,
    val servings: Int = 4,
    val tags: List<String> = emptyList(),
    val notes: String? = null,
    val ingredients: List<RecipeIngredientRequest> = emptyList(),
)

@Serializable
data class RecipeIngredientRequest(
    val ingredient: String? = null,
    val ciqualAlimCode: String? = null,
    val quantity: Float,
    val unit: String,
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

@Serializable
data class MealCreateRequest(
    val summary: String,
    val startAt: String,
    val endAt: String,
    val slot: String,
    val recipes: List<String> = emptyList(),
    val allDay: Boolean = false,
    val agenda: String? = null,
)

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
    val obligation: String = "optional",
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
)

@Serializable
data class EnvelopeCreateRequest(
    val category: String,
    val amountCents: Int,
    val year: Int,
    val mode: String = "monthly",
    val month: Int? = null,
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
data class RollOverRequest(
    val fromYear: Int,
    val year: Int,
    val fromMonth: Int? = null,
    val month: Int? = null,
    val useActualSpending: Boolean = false,
)

@Serializable
data class CategorizationRuleCreateRequest(
    val labelPattern: String,
    val category: String,
    val matchType: String = "contains",
    val direction: String = "any",
    val minAmountCents: Int? = null,
    val maxAmountCents: Int? = null,
    val priority: Int = 0,
    val isActive: Boolean = true,
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

@Serializable
data class EndErrandRemainingStore(
    val id: String,
    val name: String,
)

@Serializable
data class EndErrandResponse(
    val success: Boolean,
    val remainingItems: List<EndErrandRemainingItem> = emptyList(),
    val remainingCount: Int = 0,
)

private val MERGE_PATCH = ContentType("application", "merge-patch+json")

class MaggieApiService(
    val client: HttpClient,
) {
    private val baseUrl = BuildConfig.API_BASE_URL

    suspend fun getEvents(
        afterDate: String? = null,
        beforeDate: String? = null,
    ): List<Event> {
        return client.get("$baseUrl/api/events") {
            accept(ContentType("application", "ld+json"))
            afterDate?.let { url.parameters.append("startAt[after]", it) }
            beforeDate?.let { url.parameters.append("endAt[before]", it) }
        }.body<ApiCollection<Event>>().member
    }

    suspend fun getRecurringEventsBefore(before: String): List<Event> {
        return client.get("$baseUrl/api/events") {
            accept(ContentType("application", "ld+json"))
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
        }.body<ApiCollection<Task>>().member
    }

    suspend fun getUndoneTasks(dueDateBefore: String? = null): List<Task> {
        return client.get("$baseUrl/api/tasks") {
            accept(ContentType("application", "ld+json"))
            url.parameters.append("exists[completedAt]", "false")
            dueDateBefore?.let { url.parameters.append("dueDate[before]", it) }
        }.body<ApiCollection<Task>>().member
    }

    suspend fun getUndoneUndatedTasks(): List<Task> {
        return client.get("$baseUrl/api/tasks") {
            accept(ContentType("application", "ld+json"))
            url.parameters.append("exists[completedAt]", "false")
            url.parameters.append("exists[dueDate]", "false")
        }.body<ApiCollection<Task>>().member
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
        }.body<ApiCollection<Agenda>>().member
    }

    suspend fun createAgenda(request: AgendaCreateRequest): Agenda {
        return client.post("$baseUrl/api/agendas") {
            contentType(ContentType.Application.Json)
            accept(ContentType("application", "ld+json"))
            setBody(request)
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

    // Chat — agent-owned endpoints
    suspend fun getMessages(userId: String = "default", afterDate: String? = null): List<ChatMessage> {
        return client.get("$baseUrl/agent/messages") {
            url.parameters.append("user_id", userId)
            afterDate?.let { url.parameters.append("after", it) }
        }.body()
    }

    suspend fun sendChat(message: String, userId: String = "default"): AgentChatResponse {
        return client.post("$baseUrl/agent/chat") {
            contentType(ContentType.Application.Json)
            setBody(AgentChatRequest(message = message, user_id = userId))
        }.body()
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
    suspend fun getContexts(): List<Context> {
        return client.get("$baseUrl/agent/contexts").body()
    }

    // Chat streaming — AG-UI SSE endpoint
    fun sendChatStream(message: String): Flow<AgUiEvent> = kotlinx.coroutines.flow.flow {
        try {
            val response = client.post("$baseUrl/agent/chat/stream") {
                contentType(ContentType.Application.Json)
                setBody(AgentChatRequest(message = message))
            }
            val status = response.status.value
            if (status != 200) {
                emit(AgUiEvent.Error("HTTP $status"))
            } else {
                val channel = response.bodyAsChannel()
                AgUiStreamParser.parseStream(channel).collect { emit(it) }
            }
        } catch (e: Exception) {
            emit(AgUiEvent.Error(e.message ?: "Stream error"))
        }
    }

    suspend fun transcribe(audioFile: File): String {
        val response = client.submitFormWithBinaryData(
            url = "$baseUrl/agent/transcribe",
            formData = formData {
                append("audio", audioFile.readBytes(), Headers.build {
                    append(HttpHeaders.ContentDisposition, "filename=\"${audioFile.name}\"")
                    append(HttpHeaders.ContentType, "audio/mp4")
                })
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
    suspend fun registerFcmToken(token: String, deviceName: String? = null) {
        client.post("$baseUrl/api/fcm_tokens") {
            contentType(ContentType.Application.Json)
            setBody(FcmTokenRequest(token = token, deviceName = deviceName))
        }
    }

    // Recipes
    suspend fun getRecipes(): List<Recipe> {
        return client.get("$baseUrl/api/recipes") {
            accept(ContentType("application", "ld+json"))
        }.body<ApiCollection<Recipe>>().member
    }

    suspend fun getRecipe(id: String): Recipe {
        return client.get("$baseUrl/api/recipes/$id") {
            accept(ContentType("application", "ld+json"))
        }.body()
    }

    suspend fun createRecipe(request: RecipeCreateRequest): Recipe {
        return client.post("$baseUrl/api/recipes") {
            contentType(ContentType.Application.Json)
            accept(ContentType("application", "ld+json"))
            setBody(request)
        }.body()
    }

    suspend fun updateRecipe(id: String, data: JsonObject): Recipe {
        return client.patch("$baseUrl/api/recipes/$id") {
            contentType(MERGE_PATCH)
            accept(ContentType("application", "ld+json"))
            setBody(data)
        }.body()
    }

    suspend fun deleteRecipe(id: String) {
        client.delete("$baseUrl/api/recipes/$id")
    }

    // Ingredients
    suspend fun getIngredients(): List<Ingredient> {
        return client.get("$baseUrl/api/ingredients") {
            accept(ContentType("application", "ld+json"))
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
    suspend fun getMeals(startAfter: String? = null, startBefore: String? = null): List<Meal> {
        return client.get("$baseUrl/api/meals") {
            accept(ContentType("application", "ld+json"))
            startAfter?.let { url.parameters.append("startAt[after]", it) }
            startBefore?.let { url.parameters.append("startAt[before]", it) }
        }.body<ApiCollection<Meal>>().member
    }

    suspend fun createMeal(request: MealCreateRequest): Meal {
        return client.post("$baseUrl/api/meals") {
            contentType(ContentType.Application.Json)
            accept(ContentType("application", "ld+json"))
            setBody(request)
        }.body()
    }

    suspend fun deleteMeal(id: String) {
        client.delete("$baseUrl/api/meals/$id")
    }

    // Grocery Lists
    suspend fun getGroceryLists(): List<GroceryList> {
        return client.get("$baseUrl/api/grocery_lists") {
            accept(ContentType("application", "ld+json"))
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

    // Recurring Grocery Items
    suspend fun getRecurringGroceryItems(): List<RecurringGroceryItem> {
        return client.get("$baseUrl/api/recurring_grocery_items") {
            accept(ContentType("application", "ld+json"))
        }.body<ApiCollection<RecurringGroceryItem>>().member
    }

    suspend fun createRecurringGroceryItem(request: RecurringGroceryItemCreateRequest): RecurringGroceryItem {
        return client.post("$baseUrl/api/recurring_grocery_items") {
            contentType(ContentType.Application.Json)
            accept(ContentType("application", "ld+json"))
            setBody(request)
        }.body()
    }

    suspend fun deleteRecurringGroceryItem(id: String) {
        client.delete("$baseUrl/api/recurring_grocery_items/$id")
    }

    // Products
    suspend fun getProducts(): List<Product> {
        return client.get("$baseUrl/api/products") {
            accept(ContentType("application", "ld+json"))
        }.body<ApiCollection<Product>>().member
    }

    suspend fun createProduct(request: ProductCreateRequest): Product {
        return client.post("$baseUrl/api/products") {
            contentType(ContentType.Application.Json)
            accept(ContentType("application", "ld+json"))
            setBody(request)
        }.body()
    }

    suspend fun deleteProduct(id: String) {
        client.delete("$baseUrl/api/products/$id")
    }

    // Stores
    suspend fun getStores(): List<Store> {
        return client.get("$baseUrl/api/stores") {
            accept(ContentType("application", "ld+json"))
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

    // Finance — Accounts
    suspend fun getAccounts(): List<Account> {
        return client.get("$baseUrl/api/accounts") {
            accept(ContentType("application", "ld+json"))
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
        }.body<ApiCollection<Category>>().member
    }

    suspend fun createCategory(request: CategoryCreateRequest): Category {
        return client.post("$baseUrl/api/categories") {
            contentType(ContentType.Application.Json)
            accept(ContentType("application", "ld+json"))
            setBody(request)
        }.body()
    }

    suspend fun deleteCategory(id: String) {
        client.delete("$baseUrl/api/categories/$id")
    }

    // Finance — Transactions
    suspend fun getTransactions(accountId: String? = null): List<Transaction> {
        return client.get("$baseUrl/api/transactions") {
            accept(ContentType("application", "ld+json"))
            url.parameters.append("order[bookedAt]", "desc")
            accountId?.let { url.parameters.append("accountId", it) }
        }.body<ApiCollection<Transaction>>().member
    }

    suspend fun createTransaction(request: TransactionCreateRequest): Transaction {
        return client.post("$baseUrl/api/transactions") {
            contentType(ContentType.Application.Json)
            accept(ContentType("application", "ld+json"))
            setBody(request)
        }.body()
    }

    suspend fun deleteTransaction(id: String) {
        client.delete("$baseUrl/api/transactions/$id")
    }

    // Finance — Envelopes (budgets)
    suspend fun getEnvelopes(): List<Envelope> {
        return client.get("$baseUrl/api/envelopes") {
            accept(ContentType("application", "ld+json"))
        }.body<ApiCollection<Envelope>>().member
    }

    suspend fun createEnvelope(request: EnvelopeCreateRequest): Envelope {
        return client.post("$baseUrl/api/envelopes") {
            contentType(ContentType.Application.Json)
            accept(ContentType("application", "ld+json"))
            setBody(request)
        }.body()
    }

    suspend fun deleteEnvelope(id: String) {
        client.delete("$baseUrl/api/envelopes/$id")
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

    suspend fun getDailyScore(year: Int, month: Int): DailyScore {
        return client.get("$baseUrl/api/finance/daily-score") {
            url.parameters.append("year", year.toString())
            url.parameters.append("month", month.toString())
        }.body()
    }

    // Finance — Categorization rules
    suspend fun getCategorizationRules(): List<CategorizationRule> {
        return client.get("$baseUrl/api/categorization_rules") {
            accept(ContentType("application", "ld+json"))
        }.body<ApiCollection<CategorizationRule>>().member
    }

    suspend fun createCategorizationRule(request: CategorizationRuleCreateRequest): CategorizationRule {
        return client.post("$baseUrl/api/categorization_rules") {
            contentType(ContentType.Application.Json)
            accept(ContentType("application", "ld+json"))
            setBody(request)
        }.body()
    }

    suspend fun deleteCategorizationRule(id: String) {
        client.delete("$baseUrl/api/categorization_rules/$id")
    }

    suspend fun applyCategorizationRules(): ApplyRulesResult {
        return client.post("$baseUrl/api/finance/apply-categorization-rules").body()
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

    // Grocery Add Item
    suspend fun addGroceryItem(request: AddGroceryItemRequest): AddGroceryItemResponse {
        return client.post("$baseUrl/api/grocery/add-item") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()
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

    // Proactions — agent endpoint
    suspend fun getProactions(): List<Proaction> {
        return client.get("$baseUrl/agent/proactions").body()
    }

    // TTS — agent endpoint
    suspend fun getTtsVoices(): List<TtsVoice> {
        return client.get("$baseUrl/agent/tts/voices").body()
    }

    suspend fun getTtsVoice(): String {
        val response: TtsVoiceResponse = client.get("$baseUrl/agent/tts/voice").body()
        return response.voice
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
}

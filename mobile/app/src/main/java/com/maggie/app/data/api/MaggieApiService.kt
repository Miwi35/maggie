package com.maggie.app.data.api

import com.maggie.app.BuildConfig
import com.maggie.app.data.model.Agenda
import com.maggie.app.data.model.ChatMessage
import com.maggie.app.data.model.Event
import com.maggie.app.data.model.GoogleCalendar
import com.maggie.app.data.model.Task
import com.maggie.app.data.model.User
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.accept
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

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
data class FcmTokenRequest(
    val token: String,
    val deviceName: String? = null,
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

    // FCM Token
    suspend fun registerFcmToken(token: String, deviceName: String? = null) {
        client.post("$baseUrl/api/fcm_tokens") {
            contentType(ContentType.Application.Json)
            setBody(FcmTokenRequest(token = token, deviceName = deviceName))
        }
    }
}

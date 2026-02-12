package com.maggie.app.data.api

import com.maggie.app.BuildConfig
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.client.request.accept
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ChatRequest(val message: String, val user_id: String = "default")

@Serializable
data class ChatResponse(val response: String, val tool_calls: List<Map<String, String>> = emptyList())

@Serializable
data class ApiCollection<T>(val member: List<T> = emptyList())

class MaggieApiService {
    private val baseUrl = BuildConfig.API_BASE_URL

    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
    }

    suspend fun getEvents(
        afterDate: String? = null,
        beforeDate: String? = null,
    ): List<com.maggie.app.data.model.Event> {
        return client.get("$baseUrl/api/events") {
            accept(ContentType("application", "ld+json"))
            afterDate?.let { url.parameters.append("startAt[after]", it) }
            beforeDate?.let { url.parameters.append("endAt[before]", it) }
        }.body<ApiCollection<com.maggie.app.data.model.Event>>().member
    }

    suspend fun getTasks(): List<com.maggie.app.data.model.Task> {
        return client.get("$baseUrl/api/tasks") {
            accept(ContentType("application", "ld+json"))
        }.body<ApiCollection<com.maggie.app.data.model.Task>>().member
    }

    suspend fun sendChat(message: String): ChatResponse {
        return client.post("$baseUrl/agent/chat") {
            contentType(ContentType.Application.Json)
            setBody(ChatRequest(message = message))
        }.body()
    }
}

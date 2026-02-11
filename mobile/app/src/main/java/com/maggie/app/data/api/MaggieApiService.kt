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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ChatRequest(val message: String, val user_id: String = "default")

@Serializable
data class ChatResponse(val response: String, val tool_calls: List<Map<String, String>> = emptyList())

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

    suspend fun getEvents(): List<com.maggie.app.data.model.Event> {
        return client.get("$baseUrl/api/events").body()
    }

    suspend fun sendChat(message: String): ChatResponse {
        return client.post("$baseUrl/agent/chat") {
            contentType(ContentType.Application.Json)
            setBody(ChatRequest(message = message))
        }.body()
    }
}

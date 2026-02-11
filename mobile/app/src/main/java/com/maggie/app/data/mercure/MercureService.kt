package com.maggie.app.data.mercure

import com.maggie.app.BuildConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.plugins.sse.sse
import io.ktor.http.URLBuilder
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlin.time.Duration

data class MercureEvent(
    val id: String? = null,
    val type: String? = null,
    val data: String = "",
)

class MercureService(
    private val hubUrl: String = BuildConfig.MERCURE_URL,
    private val client: HttpClient = defaultClient(),
) {
    fun subscribe(topic: String): Flow<MercureEvent> = callbackFlow {
        val url = URLBuilder(hubUrl).apply {
            parameters.append("topic", topic)
        }.buildString()

        client.sse(url) {
            incoming.collect { event ->
                trySend(
                    MercureEvent(
                        id = event.id,
                        type = event.event,
                        data = event.data ?: "",
                    )
                )
            }
        }

        awaitClose()
    }

    companion object {
        fun defaultClient(): HttpClient = HttpClient(OkHttp) {
            install(SSE) {
                reconnectionTime = Duration.parse("3s")
            }
        }

        fun buildSubscriptionUrl(hubUrl: String, topic: String): String {
            return URLBuilder(hubUrl).apply {
                parameters.append("topic", topic)
            }.buildString()
        }
    }
}

package com.maggie.app.data.mercure

import android.util.Log
import com.maggie.app.BuildConfig
import com.maggie.app.data.auth.AuthRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.header
import io.ktor.http.URLBuilder
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

data class MercureEvent(
    val id: String? = null,
    val type: String? = null,
    val data: String = "",
)

class MercureService(
    private val authRepository: AuthRepository,
    private val hubUrl: String = BuildConfig.MERCURE_URL,
    private val client: HttpClient = defaultClient(),
) {
    fun subscribe(topic: String): Flow<MercureEvent> = callbackFlow {
        val url = URLBuilder(hubUrl).apply {
            parameters.append("topic", topic)
        }.buildString()

        Log.i(TAG, "Subscribing to Mercure: $url")

        while (isActive) {
            try {
                val mercureToken = authRepository.getMercureToken()
                client.sse(url, request = {
                    mercureToken?.let { header("Authorization", "Bearer $it") }
                }) {
                    Log.i(TAG, "SSE connected to $topic")
                    incoming.collect { event ->
                        Log.d(TAG, "SSE event received on $topic: ${event.data?.take(100)}")
                        trySend(
                            MercureEvent(
                                id = event.id,
                                type = event.event,
                                data = event.data ?: "",
                            )
                        )
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "SSE error on $topic: ${e::class.simpleName}: ${e.message}")
            }

            if (isActive) {
                Log.i(TAG, "SSE disconnected from $topic, reconnecting in 3s...")
                delay(3000)
            }
        }

        awaitClose()
    }

    companion object {
        private const val TAG = "MercureService"

        fun defaultClient(): HttpClient {
            val okhttp = OkHttpClient.Builder()
                .readTimeout(0, TimeUnit.SECONDS)
                .callTimeout(0, TimeUnit.SECONDS)
                .build()

            return HttpClient(OkHttp) {
                engine {
                    preconfigured = okhttp
                }
                install(SSE) {
                    reconnectionTime = Duration.parse("3s")
                }
            }
        }

        fun buildSubscriptionUrl(hubUrl: String, topic: String): String {
            return URLBuilder(hubUrl).apply {
                parameters.append("topic", topic)
            }.buildString()
        }
    }
}

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
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.isActive
import okhttp3.Dispatcher
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

data class MercureEvent(
    val id: String? = null,
    val type: String? = null,
    val data: String = "",
)

const val MERCURE_BURST_WINDOW_MS = 500L

/** Updates arriving together (one change announced on several topics, a sync's rows) count as one. */
@OptIn(FlowPreview::class)
fun Flow<MercureEvent>.coalesced(): Flow<MercureEvent> = debounce(MERCURE_BURST_WINDOW_MS)

class MercureService(
    private val authRepository: AuthRepository,
    private val hubUrl: String = BuildConfig.MERCURE_URL,
    private val client: HttpClient = defaultClient(),
) {
    fun subscribe(topic: String): Flow<MercureEvent> = callbackFlow {
        val url = buildSubscriptionUrl(hubUrl, topic)

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

        fun defaultClient(): HttpClient = HttpClient(OkHttp) {
            engine {
                // Ktor puts a fresh Dispatcher on the builder before this block runs, so the limits
                // must be set here. Each open stream holds a call for good and OkHttp queues any
                // beyond 5 per host: a recipe sheet opened over chat, approvals, contexts and the
                // dashboard would never connect.
                config {
                    readTimeout(0, TimeUnit.SECONDS)
                    callTimeout(0, TimeUnit.SECONDS)
                    dispatcher(Dispatcher().apply {
                        maxRequests = 64
                        maxRequestsPerHost = 32
                    })
                }
            }
            install(SSE) {
                reconnectionTime = Duration.parse("3s")
            }
        }

        private val PLACEHOLDER = Regex("\\{(\\w+)\\}")

        /**
         * Mercure 1.0 refuses the 0.x `topic` parameter with a 400. An exact
         * topic is subscribed with `match`; one carrying a `{id}` placeholder
         * with `match_urlpattern`, where URL Patterns spell it `:id`.
         */
        fun buildSubscriptionUrl(hubUrl: String, topic: String): String {
            return URLBuilder(hubUrl).apply {
                if (PLACEHOLDER.containsMatchIn(topic)) {
                    parameters.append("match_urlpattern", PLACEHOLDER.replace(topic, ":$1"))
                } else {
                    parameters.append("match", topic)
                }
            }.buildString()
        }
    }
}

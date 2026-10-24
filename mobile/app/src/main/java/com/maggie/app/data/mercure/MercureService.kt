package com.maggie.app.data.mercure

import android.util.Log
import com.maggie.app.BuildConfig
import com.maggie.app.data.api.installJourneyHeader
import com.maggie.app.data.auth.AuthRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.header
import io.ktor.http.URLBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Dispatcher
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

data class MercureEvent(
    val id: String? = null,
    val type: String? = null,
    val data: String = "",
)

const val MERCURE_BURST_WINDOW_MS = 500L
const val MERCURE_INDEX_RECHECK_MS = 1_500L

/**
 * Updates arriving together (one change announced on several topics, a sync's rows) count as one.
 * A stream with gaps shorter than the window emits only once it stops.
 */
@OptIn(FlowPreview::class)
fun Flow<MercureEvent>.coalesced(): Flow<MercureEvent> = debounce(MERCURE_BURST_WINDOW_MS)

/**
 * [coalesced], then the same value once more [MERCURE_INDEX_RECHECK_MS] later, unless a newer burst
 * comes first: an update is published before a worker has indexed the row and the lists are served
 * from Elasticsearch, so the first reload can miss the change.
 */
fun Flow<MercureEvent>.coalescedAndRechecked(): Flow<MercureEvent> =
    coalesced().transformLatest {
        emit(it)
        delay(MERCURE_INDEX_RECHECK_MS)
        emit(it)
    }

/**
 * What the stream announced in each burst, in one list: a value is emitted once the stream has been
 * quiet for [MERCURE_BURST_WINDOW_MS], carrying everything received since the last emission.
 */
fun <T> Flow<T>.batched(): Flow<List<T>> = channelFlow {
    val inbox = Channel<T>(Channel.UNLIMITED)
    launch {
        try {
            collect { inbox.send(it) }
            inbox.close()
        } catch (e: Throwable) {
            inbox.close(e)
        }
    }
    val batch = mutableListOf<T>()
    var open = true
    while (open) {
        val first = inbox.receiveCatching()
        batch += first.getOrNull() ?: break
        while (true) {
            val next = withTimeoutOrNull(MERCURE_BURST_WINDOW_MS) { inbox.receiveCatching() } ?: break
            val value = next.getOrNull()
            if (value == null) {
                open = false
                break
            }
            batch += value
        }
        send(batch.toList())
        batch.clear()
    }
    inbox.cancel()
}

/** [batched], then the same batch once more [MERCURE_INDEX_RECHECK_MS] later, unless a newer burst comes first (see [coalescedAndRechecked]). */
@OptIn(ExperimentalCoroutinesApi::class)
fun <T> Flow<T>.batchedAndRechecked(): Flow<List<T>> =
    batched().transformLatest {
        emit(it)
        delay(MERCURE_INDEX_RECHECK_MS)
        emit(it)
    }

/**
 * Ktor's OkHttp engine never cancels the call behind an SSE stream when its coroutine is cancelled:
 * the socket stays open and the call keeps its slot in the dispatcher until the hub hangs up. A
 * stream tags its request with an id, the client's interceptor records the call under it (and strips
 * the header), and the stream cancels that call when it ends.
 */
internal object StreamCalls {
    const val HEADER = "X-Maggie-Stream"
    private val calls = ConcurrentHashMap<String, Call>()
    private val cancelledEarly = ConcurrentHashMap.newKeySet<String>()

    fun register(id: String, call: Call) {
        if (cancelledEarly.remove(id)) call.cancel() else calls[id] = call
    }

    fun release(id: String, cancelled: Boolean) {
        val call = calls.remove(id)
        if (call != null) call.cancel() else if (cancelled) cancelledEarly.add(id)
    }
}

class MercureService(
    private val authRepository: AuthRepository,
    private val hubUrl: String = BuildConfig.MERCURE_URL,
    private val client: HttpClient = defaultClient(),
    private val reconnectDelayMs: Long = 3_000,
) {
    private val reconnected = MutableSharedFlow<String>(extraBufferCapacity = 16)

    /**
     * Emits each time the stream of [topic] is connected again after a drop. The hub replays what was
     * missed from the last event id when it still holds it ([subscribe] sends it); a subscriber that
     * must not show a stale state reloads on this signal for the case where it does not.
     */
    fun reconnections(topic: String): Flow<Unit> = reconnected.filter { it == topic }.map { }

    fun subscribe(topic: String): Flow<MercureEvent> = callbackFlow {
        val url = buildSubscriptionUrl(hubUrl, topic)

        Log.i(TAG, "Subscribing to Mercure: $url")

        var lastEventId: String? = null
        var connectedBefore = false

        while (isActive) {
            val streamId = UUID.randomUUID().toString()
            try {
                val mercureToken = authRepository.getMercureToken()
                client.sse(url, request = {
                    header(StreamCalls.HEADER, streamId)
                    mercureToken?.let { header("Authorization", "Bearer $it") }
                    lastEventId?.let { header("Last-Event-ID", it) }
                }) {
                    Log.i(TAG, "SSE connected to $topic")
                    if (connectedBefore) reconnected.tryEmit(topic)
                    connectedBefore = true
                    incoming.collect { event ->
                        Log.d(TAG, "SSE event received on $topic: ${event.data?.take(100)}")
                        event.id?.let { lastEventId = it }
                        trySend(
                            MercureEvent(
                                id = event.id,
                                type = event.event,
                                data = event.data ?: "",
                            )
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "SSE error on $topic: ${e::class.simpleName}: ${e.message}")
            } finally {
                StreamCalls.release(streamId, cancelled = !isActive)
            }

            if (isActive) {
                Log.i(TAG, "SSE disconnected from $topic, reconnecting in ${reconnectDelayMs}ms...")
                delay(reconnectDelayMs)
            }
        }

        awaitClose()
    }

    companion object {
        private const val TAG = "MercureService"

        fun streamDispatcher(): Dispatcher = Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = 32
        }

        fun defaultClient(dispatcher: Dispatcher = streamDispatcher()): HttpClient = HttpClient(OkHttp) {
            engine {
                // Ktor puts a fresh Dispatcher on the builder before this block runs, so the limits
                // must be set here. Each open stream holds a call for good and OkHttp queues any
                // beyond 5 per host: a recipe sheet opened over chat, approvals, contexts and the
                // dashboard would never connect.
                config {
                    readTimeout(0, TimeUnit.SECONDS)
                    callTimeout(0, TimeUnit.SECONDS)
                    dispatcher(dispatcher)
                    addInterceptor { chain ->
                        val request = chain.request()
                        val id = request.header(StreamCalls.HEADER)
                        if (id == null) {
                            chain.proceed(request)
                        } else {
                            StreamCalls.register(id, chain.call())
                            chain.proceed(request.newBuilder().removeHeader(StreamCalls.HEADER).build())
                        }
                    }
                }
            }
            install(SSE) {
                reconnectionTime = Duration.parse("3s")
            }
            // The Mercure streams carry the e2e journey too; nothing in dev and prod.
            installJourneyHeader()
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

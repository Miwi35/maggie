package com.maggie.app.data.mercure

import io.ktor.client.plugins.sse.sse
import io.ktor.http.Url
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class MercureServiceTest {

    // Through the real client: the limits must reach the engine, which Ktor
    // builds with a fresh dispatcher of its own (9 Oct.: one thread per
    // stream and 30 s, after two red CI runs on a loaded runner).
    @Test
    fun `the default client holds more than five streams to one host at once`() = runBlocking {
        val streams = 8
        val connected = CountDownLatch(streams)
        val sockets = mutableListOf<Socket>()
        val server = ServerSocket(0)
        val accepting = thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (e: Exception) { return@thread }
                synchronized(sockets) { sockets += socket }
                // One thread per stream: a slow client must not hold the others back.
                thread(isDaemon = true) {
                    try {
                        socket.getInputStream().read(ByteArray(4096))
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n\r\n: hello\n\n".toByteArray())
                            flush()
                        }
                    } catch (e: Exception) {
                        // Closed by the test's cleanup.
                    }
                }
            }
        }
        val client = MercureService.defaultClient()

        try {
            val jobs = List(streams) {
                launch(Dispatchers.IO) {
                    client.sse("http://127.0.0.1:${server.localPort}/hub") {
                        connected.countDown()
                        incoming.collect { }
                    }
                }
            }

            assertTrue(
                "only ${streams - connected.count} of $streams streams connected",
                withContext(Dispatchers.IO) { connected.await(30, TimeUnit.SECONDS) },
            )
            jobs.forEach { it.cancel() }
        } finally {
            client.close()
            server.close()
            synchronized(sockets) { sockets.forEach { it.close() } }
            accepting.join(1000)
        }
    }

    // A screen that closes cancels its streams, and the next one must still connect: every
    // journey opens a few dozen over one session, and past the dispatcher's per-host cap a late
    // subscriber (the open recipe, the chat) never receives anything (MAG-512). Ktor's engine does
    // not hang up on cancellation, so the proof is the hub seeing each socket close, not the queue
    // moving: it goes through MercureService.subscribe and the default limits (32 per host).
    @Test
    fun `cancelling a stream closes its connection and frees its slot`() = runBlocking {
        val streams = 80
        val connected = LinkedBlockingQueue<Unit>()
        val closedByClient = CountDownLatch(streams)
        val tagReachedHub = AtomicBoolean(false)
        val sockets = mutableListOf<Socket>()
        val server = ServerSocket(0)
        val accepting = thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (e: Exception) { return@thread }
                synchronized(sockets) { sockets += socket }
                thread(isDaemon = true) {
                    try {
                        val input = socket.getInputStream()
                        val head = StringBuilder()
                        while (!head.endsWith("\r\n\r\n")) {
                            val byte = input.read()
                            if (byte == -1) return@thread
                            head.append(byte.toChar())
                        }
                        if (head.contains(StreamCalls.HEADER, ignoreCase = true)) tagReachedHub.set(true)
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n\r\n: hello\n\n".toByteArray())
                            flush()
                        }
                        connected.put(Unit)
                        while (input.read() != -1) {
                            // Hold the stream open until the client hangs up.
                        }
                        closedByClient.countDown()
                    } catch (e: Exception) {
                        closedByClient.countDown()
                    }
                }
            }
        }
        io.mockk.mockkStatic(android.util.Log::class)
        io.mockk.every { android.util.Log.i(any(), any<String>()) } returns 0
        io.mockk.every { android.util.Log.d(any(), any<String>()) } returns 0
        io.mockk.every { android.util.Log.w(any(), any<String>()) } returns 0
        val authRepository = io.mockk.mockk<com.maggie.app.data.auth.AuthRepository>()
        io.mockk.coEvery { authRepository.getMercureToken() } returns null
        val dispatcher = MercureService.streamDispatcher()
        val client = MercureService.defaultClient(dispatcher)
        val service = MercureService(authRepository, "http://127.0.0.1:${server.localPort}/hub", client)

        try {
            repeat(streams) { index ->
                val job = launch(Dispatchers.IO) { service.subscribe("/agent/chat/default").collect { } }
                assertNotNull(
                    "stream ${index + 1} of $streams never connected",
                    withContext(Dispatchers.IO) { connected.poll(10, TimeUnit.SECONDS) },
                )
                job.cancelAndJoin()
            }

            assertTrue(
                "the hub saw only ${streams - closedByClient.count} of $streams connections close",
                withContext(Dispatchers.IO) { closedByClient.await(15, TimeUnit.SECONDS) },
            )
            assertFalse("the stream tag must not reach the hub", tagReachedHub.get())
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (dispatcher.runningCallsCount() > 0 && System.nanoTime() < deadline) delay(50)
            assertEquals(0, dispatcher.runningCallsCount())
        } finally {
            io.mockk.unmockkStatic(android.util.Log::class)
            client.close()
            server.close()
            synchronized(sockets) { sockets.forEach { it.close() } }
            accepting.join(1000)
        }
    }

    // A hub heartbeat is a comment line (": ..."), not an update: handing it to a subscriber makes
    // every idle screen reload on each beat (MAG-372).
    // No fixed sleep: the fake hub holds the stream open until the client hangs up, so a slow runner
    // cannot lose the beats to an early close. The timeout is only a ceiling (red once at 10 s, 11 Oct.).
    @Test
    fun `a hub heartbeat is not handed to subscribers as an update`() = runBlocking {
        val server = ServerSocket(0)
        val accepting = thread(isDaemon = true) {
            val socket = try { server.accept() } catch (e: Exception) { return@thread }
            try {
                val input = socket.getInputStream()
                val head = StringBuilder()
                while (!head.endsWith("\r\n\r\n")) {
                    val byte = input.read()
                    if (byte == -1) return@thread
                    head.append(byte.toChar())
                }
                socket.getOutputStream().apply {
                    write(
                        ("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n\r\n" +
                            ":\n\n: heartbeat\n\nid: 1\ndata: {\"title\":\"x\"}\n\n").toByteArray(),
                    )
                    flush()
                }
                while (input.read() != -1) {
                    // Hold the stream open until the client closes it.
                }
            } catch (e: Exception) {
                // Closed by the test's cleanup.
            } finally {
                socket.close()
            }
        }
        io.mockk.mockkStatic(android.util.Log::class)
        io.mockk.every { android.util.Log.i(any(), any<String>()) } returns 0
        io.mockk.every { android.util.Log.d(any(), any<String>()) } returns 0
        io.mockk.every { android.util.Log.w(any(), any<String>()) } returns 0
        val authRepository = io.mockk.mockk<com.maggie.app.data.auth.AuthRepository>()
        io.mockk.coEvery { authRepository.getMercureToken() } returns null
        val client = MercureService.defaultClient()
        val service = MercureService(authRepository, "http://127.0.0.1:${server.localPort}/hub", client)

        try {
            val first = kotlinx.coroutines.withTimeout(30_000) {
                service.subscribe("/users/u/api/events/{id}").first()
            }
            assertEquals("{\"title\":\"x\"}", first.data)
        } finally {
            io.mockk.unmockkStatic(android.util.Log::class)
            client.close()
            server.close()
            accepting.join(3_000)
        }
    }

    @Test
    fun `buildSubscriptionUrl subscribes a placeholder topic as a URL pattern`() {
        val url = MercureService.buildSubscriptionUrl(
            hubUrl = "http://maggie.local/.well-known/mercure",
            topic = "/users/user-1/api/events/{id}"
        )

        assertTrue(url.startsWith("http://maggie.local/.well-known/mercure?"))
        val parsed = Url(url)
        assertEquals("/users/user-1/api/events/:id", parsed.parameters["match_urlpattern"])
        assertNull(parsed.parameters["match"])
    }

    @Test
    fun `buildSubscriptionUrl subscribes an exact topic with match, never the 0x topic parameter`() {
        val url = MercureService.buildSubscriptionUrl(
            hubUrl = "http://localhost/.well-known/mercure",
            topic = "/agent/chat/default"
        )

        val parsed = Url(url)
        assertEquals("/agent/chat/default", parsed.parameters["match"])
        assertNull(parsed.parameters["match_urlpattern"])
        assertNull(parsed.parameters["topic"])
    }

    @Test
    fun `MercureEvent data class defaults`() {
        val event = MercureEvent(data = "{\"test\": true}")

        assertEquals(null, event.id)
        assertEquals(null, event.type)
        assertEquals("{\"test\": true}", event.data)
    }

    @Test
    fun `MercureEvent data class with all fields`() {
        val event = MercureEvent(
            id = "msg-1",
            type = "message",
            data = "{\"response\": \"hello\"}"
        )

        assertEquals("msg-1", event.id)
        assertEquals("message", event.type)
        assertEquals("{\"response\": \"hello\"}", event.data)
    }
}

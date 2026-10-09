package com.maggie.app.data.mercure

import io.ktor.client.plugins.sse.sse
import io.ktor.http.Url
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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

    // A hub heartbeat is a comment line (": ..."), not an update: handing it to a subscriber makes
    // every idle screen reload on each beat (MAG-372).
    @Test
    fun `a hub heartbeat is not handed to subscribers as an update`() = runBlocking {
        val server = ServerSocket(0)
        val accepting = thread(isDaemon = true) {
            val socket = try { server.accept() } catch (e: Exception) { return@thread }
            try {
                socket.getInputStream().read(ByteArray(4096))
                socket.getOutputStream().apply {
                    write(
                        ("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n\r\n" +
                            ":\n\n: heartbeat\n\nid: 1\ndata: {\"title\":\"x\"}\n\n").toByteArray(),
                    )
                    flush()
                }
                Thread.sleep(2_000)
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
            val first = kotlinx.coroutines.withTimeout(10_000) {
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

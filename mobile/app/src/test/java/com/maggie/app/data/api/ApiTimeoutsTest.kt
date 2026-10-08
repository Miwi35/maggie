package com.maggie.app.data.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.ServerSocket
import kotlin.concurrent.thread

/** MAG-319: an answer that takes longer than OkHttp's 10 s was dropped while the agent had stored it. */
class ApiTimeoutsTest {

    /** Answers any request with "ok", [afterMs] after it came in. */
    private class SlowServer(afterMs: Long) : AutoCloseable {
        private val socket = ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
        val url = "http://127.0.0.1:${socket.localPort}/slow"

        init {
            thread(isDaemon = true) {
                try {
                    socket.accept().use { client ->
                        val reader = client.getInputStream().bufferedReader()
                        while (reader.readLine().orEmpty().isNotEmpty()) { /* request head */ }
                        Thread.sleep(afterMs)
                        client.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".toByteArray())
                            flush()
                        }
                    }
                } catch (_: Exception) {
                    // The client gave up first.
                }
            }
        }

        override fun close() = socket.close()
    }

    private fun productionClient() = HttpClient(OkHttp) { installApiTimeouts() }

    @Test
    fun `an agent reply that takes 12 s arrives`() {
        SlowServer(12_000).use { server ->
            val text = runBlocking { productionClient().post(server.url) { waitForAgentReply() }.bodyAsText() }
            assertEquals("ok", text)
        }
    }

    @Test
    fun `any other call still gives up after 10 s`() {
        SlowServer(12_000).use { server ->
            try {
                runBlocking { productionClient().get(server.url).bodyAsText() }
                fail("A call that is not an agent reply must not wait 12 s")
            } catch (e: Exception) {
                assertTrue(e::class.simpleName.orEmpty().contains("Timeout"))
            }
        }
    }

    @Test
    fun `the chat calls ask for their longer waits`() {
        val seen = mutableMapOf<String, Pair<Long?, Long?>>()
        val engine = MockEngine { request ->
            val timeout = request.getCapabilityOrNull(io.ktor.client.plugins.HttpTimeoutCapability)
            seen[request.url.encodedPath] = timeout?.socketTimeoutMillis to timeout?.requestTimeoutMillis
            respond(
                content = """{"response":"","messages":[]}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = HttpClient(engine) {
            install(HttpTimeout)
            install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) {
                json()
            }
        }
        val service = MaggieApiService(client)

        runBlocking {
            service.sendChat("Quel temps demain ?")
            service.sendChatStream("Quel temps demain ?").collect { }
        }

        val chat = seen["/agent/chat"]
        assertNotNull(chat)
        assertEquals(ApiTimeouts.CHAT_MS, chat!!.first)
        assertEquals(ApiTimeouts.CHAT_MS, chat.second)
        val stream = seen["/agent/chat/stream"]
        assertNotNull(stream)
        assertEquals(ApiTimeouts.STREAM_SILENCE_MS, stream!!.first)
        assertEquals(ApiTimeouts.STREAM_TOTAL_MS, stream.second)
    }
}

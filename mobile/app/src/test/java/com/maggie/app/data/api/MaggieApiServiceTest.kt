package com.maggie.app.data.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class MaggieApiServiceTest {

    @Test
    fun `AgentChatRequest serializes with defaults`() {
        val request = AgentChatRequest(message = "Hello")
        assertEquals("Hello", request.message)
        assertEquals("default", request.user_id)
    }

    @Test
    fun `FcmTokenRequest serializes correctly`() {
        val request = FcmTokenRequest(token = "abc123", deviceName = "Pixel 8")
        assertEquals("abc123", request.token)
        assertEquals("Pixel 8", request.deviceName)
    }

    @Test
    fun `FcmTokenRequest deviceName defaults to null`() {
        val request = FcmTokenRequest(token = "abc123")
        assertNull(request.deviceName)
    }

    @Test
    fun `TranscribeResponse deserializes clean and raw`() {
        val json = Json { ignoreUnknownKeys = true }
        val response = json.decodeFromString<TranscribeResponse>("""{"raw":"euh bonjour","clean":"Bonjour."}""")
        assertEquals("euh bonjour", response.raw)
        assertEquals("Bonjour.", response.clean)
    }

    @Test
    fun `TranscribeResponse defaults to empty strings`() {
        val json = Json { ignoreUnknownKeys = true }
        val response = json.decodeFromString<TranscribeResponse>("""{}""")
        assertEquals("", response.raw)
        assertEquals("", response.clean)
    }

    @Test
    fun `transcribe sends multipart POST and returns clean text`() = runBlocking {
        var capturedMethod: HttpMethod? = null
        var capturedUrl: String? = null
        var capturedContentType: String? = null

        val mockEngine = MockEngine { request ->
            capturedMethod = request.method
            capturedUrl = request.url.toString()
            capturedContentType = request.body.contentType?.toString()
            respond(
                content = ByteReadChannel("""{"raw":"euh bonjour","clean":"Bonjour."}"""),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        val client = HttpClient(mockEngine) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; isLenient = true })
            }
        }

        val service = MaggieApiService(client)

        val tempFile = File.createTempFile("test_audio", ".m4a")
        tempFile.writeBytes(ByteArray(100) { it.toByte() })

        try {
            val result = service.transcribe(tempFile)

            assertEquals("Bonjour.", result)
            assertEquals(HttpMethod.Post, capturedMethod)
            assertTrue("URL should end with /agent/transcribe", capturedUrl!!.endsWith("/agent/transcribe"))
            assertTrue("Content type should be multipart", capturedContentType!!.startsWith("multipart/form-data"))
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `transcribe falls back to raw when clean is blank`() = runBlocking {
        val mockEngine = MockEngine {
            respond(
                content = ByteReadChannel("""{"raw":"euh bonjour","clean":""}"""),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        val client = HttpClient(mockEngine) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; isLenient = true })
            }
        }

        val service = MaggieApiService(client)
        val tempFile = File.createTempFile("test_audio", ".m4a")
        tempFile.writeBytes(ByteArray(100) { it.toByte() })

        try {
            val result = service.transcribe(tempFile)
            assertEquals("euh bonjour", result)
        } finally {
            tempFile.delete()
        }
    }

    /**
     * « Terminé » on a shop (MAG-242). The screen half is `GroceryScreenTest` over a
     * fake repository and the server half is `EndErrandControllerTest`, so this is
     * the only thing that reads the request the app actually sends and the answer it
     * actually parses: `/api/grocery/end-errand` is a controller of its own, not an
     * API Platform operation, so it is in none of the recorded responses of
     * `api/contract/`. The live round trip stays in `08-grocery-realtime`.
     */
    @Test
    fun `endErrand posts the store and reads the lines offered back`() = runBlocking {
        var capturedMethod: HttpMethod? = null
        var capturedUrl: String? = null
        var capturedBody: String? = null

        val mockEngine = MockEngine { request ->
            capturedMethod = request.method
            capturedUrl = request.url.toString()
            capturedBody = (request.body as io.ktor.http.content.TextContent).text
            respond(
                content = ByteReadChannel(
                    """{"success":true,"remainingCount":1,"remainingItems":[
                       {"id":"item-1","label":"Timbres du voisin","quantity":2,"unit":"piece",
                        "store":{"id":"store-corner","name":"Épicerie du coin"}}]}""",
                ),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        val client = HttpClient(mockEngine) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; isLenient = true })
            }
        }

        val response = MaggieApiService(client).endErrand(EndErrandRequest(storeId = "store-corner"))

        assertEquals(HttpMethod.Post, capturedMethod)
        assertTrue("URL should end with /api/grocery/end-errand", capturedUrl!!.endsWith("/api/grocery/end-errand"))
        assertTrue("the shop is in the body", capturedBody!!.contains("\"storeId\":\"store-corner\""))
        assertTrue(response.success)
        assertEquals(1, response.remainingCount)
        assertEquals(listOf("Timbres du voisin"), response.remainingItems.map { it.label })
        assertEquals("Épicerie du coin", response.remainingItems.single().store?.name)
    }
}

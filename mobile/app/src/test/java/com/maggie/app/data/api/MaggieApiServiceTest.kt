package com.maggie.app.data.api

import com.maggie.app.data.model.toBuyLabel
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class MaggieApiServiceTest {

    /** The value of the multipart `cleanup` field, read off its own part. */
    private fun cleanupPartOf(body: ByteArray): String? =
        // Between the disposition line and the value, Ktor writes the part's own
        // Content-Length, hence the lazy gap.
        Regex("""name="?cleanup"?.*?\r?\n\r?\n([^\r\n-]*)""", RegexOption.DOT_MATCHES_ALL)
            .find(String(body, Charsets.ISO_8859_1))
            ?.groupValues
            ?.get(1)
            ?.trim()

    @Test
    fun `AgentChatRequest serializes with defaults`() {
        val request = AgentChatRequest(message = "Hello")
        assertEquals("Hello", request.message)
        assertEquals("default", request.user_id)
        assertNull(request.screen_context)
    }

    @Test
    fun `AgentChatRequest carries the screen context beside the message, not inside it`() {
        // Glued to the message, the block was stored as the message and came back in the
        // user's bubble on every client (MAG-30, refused recette). The agent reads this
        // field, keeps it out of the history, and hands it to the model alone.
        val block = "[Contexte de l'écran]\nPage : https://dice.fm/event/x"
        val body = Json.encodeToString(
            AgentChatRequest.serializer(),
            AgentChatRequest(message = "De quoi parle cette page ?", screen_context = block),
        )

        assertTrue(body.contains("\"screen_context\""))
        assertTrue(body.contains("\"message\":\"De quoi parle cette page ?\""))
    }

    @Test
    fun `AgentChatRequest carries the idempotency key the agent deduplicates a resent message on`() {
        val body = Json.encodeToString(
            AgentChatRequest.serializer(),
            AgentChatRequest(message = "Bonjour", idempotency_key = "k-1"),
        )

        assertTrue(body.contains("\"idempotency_key\":\"k-1\""))
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
    fun `transcribe asks for no cleanup by default and labels a wav clip`() = runBlocking {
        var body: ByteArray? = null

        val mockEngine = MockEngine { request ->
            body = request.body.toByteArray()
            respond(
                content = ByteReadChannel("""{"raw":"euh bonjour","clean":"bonjour"}"""),
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
        val tempFile = File.createTempFile("test_audio", ".wav")
        tempFile.writeBytes(ByteArray(100) { it.toByte() })

        try {
            service.transcribe(tempFile)

            // Talking to Maggie pays for no cleanup (MAG-222), and Whisper is told the
            // clip is WAV — the microphone now records PCM, for the phone's own engine.
            // Read off the `cleanup` part itself: the body also carries a hundred bytes
            // of arbitrary audio, in which any word can be found by chance.
            assertEquals("none", cleanupPartOf(body!!))
            assertTrue("the clip is labelled audio/wav", body!!.decodeToString().contains("audio/wav"))
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `transcribe passes the cleanup mode a dictation asks for`() = runBlocking {
        var body: ByteArray? = null

        val mockEngine = MockEngine { request ->
            body = request.body.toByteArray()
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
        val tempFile = File.createTempFile("test_audio", ".wav")
        tempFile.writeBytes(ByteArray(100) { it.toByte() })

        try {
            service.transcribe(tempFile, TranscriptCleanup.AUTO)
            assertEquals("auto", cleanupPartOf(body!!))
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `the content type follows the clip's extension`() {
        val service = MaggieApiService(HttpClient(MockEngine { respond("") }))

        assertEquals("audio/wav", service.audioContentType(File("voice.wav")))
        assertEquals("audio/webm", service.audioContentType(File("voice.webm")))
        assertEquals("audio/mp4", service.audioContentType(File("voice.m4a")))
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
            capturedBody = (request.body as TextContent).text
            respond(
                content = ByteReadChannel(
                    """{"success":true,"remainingCount":1,"restockedProducts":[{"id":"p1","name":"Riz","stockState":"in_stock"}],"restockedCount":1,"remainingItems":[
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
        assertEquals(listOf("Riz"), response.restockedProducts.map { it.name })
    }

    private fun approvalClient(
        status: HttpStatusCode = HttpStatusCode.OK,
        body: String,
        seen: MutableList<Pair<HttpMethod, String>>,
    ) = HttpClient(
        MockEngine { request ->
            seen += request.method to request.url.toString()
            respond(
                content = ByteReadChannel(body),
                status = status,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        },
    ) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; isLenient = true }) }
    }

    private val pendingJson =
        """{"id":"ap-1","toolName":"delete_event","arguments":{"id":"evt-1"},"status":"pending","expiresAt":"2026-10-07T10:00:00Z"}"""

    @Test
    fun `a refused FCM registration is an error, so the device does not look registered`() = runBlocking {
        val client = approvalClient(status = HttpStatusCode.Unauthorized, body = "{}", seen = mutableListOf())

        val error = runCatching { MaggieApiService(client).registerFcmToken("tok-1", "Pixel") }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
    }

    @Test
    fun `an FCM token is removed with a DELETE that names it in the body, never in the path`() = runBlocking {
        val seen = mutableListOf<Pair<HttpMethod, String>>()
        var body: String? = null
        val client = HttpClient(
            MockEngine { request ->
                seen += request.method to request.url.toString()
                body = String(request.body.toByteArray())
                respond(content = ByteReadChannel(""), status = HttpStatusCode.NoContent)
            },
        ) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; isLenient = true }) }
        }

        MaggieApiService(client).unregisterFcmToken("tok-secret")

        assertEquals(HttpMethod.Delete, seen.single().first)
        assertTrue(seen.single().second.endsWith("/api/fcm_tokens"))
        assertTrue(body!!.contains("tok-secret"))
    }

    @Test
    fun `getPendingApprovals asks the agent for the pending ones`() = runBlocking {
        val seen = mutableListOf<Pair<HttpMethod, String>>()
        val client = approvalClient(body = "[$pendingJson]", seen = seen)

        val approvals = MaggieApiService(client).getPendingApprovals()

        assertEquals(HttpMethod.Get, seen.single().first)
        assertTrue(seen.single().second.endsWith("/agent/approvals?status=pending"))
        assertEquals("delete_event", approvals.single().toolName)
        assertEquals("evt-1", approvals.single().arguments["id"].toString().trim('"'))
    }

    @Test
    fun `approve posts to the approve endpoint and returns the settled approval`() = runBlocking {
        val seen = mutableListOf<Pair<HttpMethod, String>>()
        val client = approvalClient(body = pendingJson.replace("pending", "approved"), seen = seen)

        val approval = MaggieApiService(client).approve("ap-1")

        assertEquals(HttpMethod.Post, seen.single().first)
        assertTrue(seen.single().second.endsWith("/agent/approvals/ap-1/approve"))
        assertEquals("approved", approval.status)
    }

    @Test
    fun `deny posts to the deny endpoint`() = runBlocking {
        val seen = mutableListOf<Pair<HttpMethod, String>>()
        val client = approvalClient(body = pendingJson.replace("pending", "denied"), seen = seen)

        val approval = MaggieApiService(client).deny("ap-1")

        assertTrue(seen.single().second.endsWith("/agent/approvals/ap-1/deny"))
        assertEquals("denied", approval.status)
    }

    @Test
    fun `an approval already decided raises a final decision error`() = runBlocking {
        for (code in listOf(404, 409, 410)) {
            val client = approvalClient(
                status = HttpStatusCode.fromValue(code),
                body = """{"detail":"gone"}""",
                seen = mutableListOf(),
            )

            val error = runCatching { MaggieApiService(client).approve("ap-1") }.exceptionOrNull()

            assertTrue("HTTP $code", error is ApprovalDecisionException)
            assertTrue("HTTP $code is final", (error as ApprovalDecisionException).isFinal)
        }
    }

    @Test
    fun `a server error is not final, the user can try again`() = runBlocking {
        val client = approvalClient(
            status = HttpStatusCode.InternalServerError,
            body = """{"detail":"boom"}""",
            seen = mutableListOf(),
        )

        val error = runCatching { MaggieApiService(client).deny("ap-1") }.exceptionOrNull()

        assertTrue(error is ApprovalDecisionException)
        assertFalse((error as ApprovalDecisionException).isFinal)
    }

    @Test
    fun `meals are asked for a whole month in one page, oldest first`() = runBlocking {
        val seen = mutableListOf<Pair<HttpMethod, String>>()
        val client = approvalClient(body = """{"member":[]}""", seen = seen)

        MaggieApiService(client).getMeals("2026-06-01", "2026-06-30")

        val url = seen.single().second
        assertTrue(url, url.contains("itemsPerPage=100"))
        assertTrue(url, url.contains("order%5Bdate%5D=asc") || url.contains("order[date]=asc"))
        assertTrue(url, url.contains("date%5Bafter%5D=2026-06-01") || url.contains("date[after]=2026-06-01"))
        assertTrue(url, url.contains("date%5Bbefore%5D=2026-06-30") || url.contains("date[before]=2026-06-30"))
    }

    @Test
    fun `the deletion impact of a recipe is read from its own endpoint`() = runBlocking {
        var capturedUrl: String? = null
        var capturedMethod: HttpMethod? = null

        val client = HttpClient(
            MockEngine { request ->
                capturedUrl = request.url.toString()
                capturedMethod = request.method
                respond(
                    content = ByteReadChannel("""{"mealCount":1,"meals":[{"date":"2030-01-14","slot":"dinner"}]}"""),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            },
        ) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; isLenient = true })
            }
        }

        val impact = MaggieApiService(client).getRecipeDeletionImpact("01C")

        assertEquals(1, impact.mealCount)
        assertEquals(listOf(PlannedMealRef("2030-01-14", "dinner")), impact.meals)
        assertEquals(HttpMethod.Get, capturedMethod)
        assertTrue(capturedUrl!!.endsWith("/api/recipes/01C/deletion-impact"))
    }

    private val previewJson = """{"mealId":"m1","groceryChoiceMadeAt":null,"ingredients":[
        {"ingredientId":"rice","name":"Riz","quantity":300,"unit":"g",
         "packaging":{"unit":"pack","size":500,"sizeUnit":"g"},"packagedQuantity":1,
         "toBuy":{"quantity":1,"unit":"pack"},"converted":true,"stockState":"out","suggested":true},
        {"ingredientId":"veg","name":"Légumes pour couscous","quantity":1,"unit":"jar",
         "packaging":{"unit":"jar","size":null,"sizeUnit":null},"packagedQuantity":1,
         "toBuy":{"quantity":1,"unit":"jar"},"converted":true,"stockState":"in_stock","suggested":false}]}"""

    /**
     * `grocery_preview` and `grocery_items` are controllers of their own, not API Platform
     * operations, so no recorded response of `api/contract/` covers them: this is the
     * shape the app reads and the request it sends. The live round trip is
     * `10-meal-ingredient-choice`.
     */
    @Test
    fun `the grocery preview of a meal is read from its own endpoint`() = runBlocking {
        var capturedUrl: String? = null
        var capturedMethod: HttpMethod? = null

        val client = HttpClient(
            MockEngine { request ->
                capturedUrl = request.url.toString()
                capturedMethod = request.method
                respond(
                    content = ByteReadChannel(previewJson),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            },
        ) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; isLenient = true })
            }
        }

        val preview = MaggieApiService(client).getMealGroceryPreview("m1")

        assertEquals(HttpMethod.Get, capturedMethod)
        assertTrue(capturedUrl!!.endsWith("/api/meals/m1/grocery_preview"))
        assertEquals(listOf("Riz", "Légumes pour couscous"), preview.ingredients.map { it.name })
        assertEquals(listOf(true, false), preview.ingredients.map { it.suggested })
        assertEquals("1 paquet (500 g)", preview.ingredients[0].toBuyLabel())
        assertEquals("1 bocal", preview.ingredients[1].toBuyLabel())
    }

    @Test
    fun `the chosen ingredients are posted to the meal and the preview read back`() = runBlocking {
        var capturedUrl: String? = null
        var capturedMethod: HttpMethod? = null
        var capturedBody: String? = null

        val client = HttpClient(
            MockEngine { request ->
                capturedUrl = request.url.toString()
                capturedMethod = request.method
                capturedBody = (request.body as TextContent).text
                respond(
                    content = ByteReadChannel(previewJson.replace("\"groceryChoiceMadeAt\":null", "\"groceryChoiceMadeAt\":\"2030-01-01T10:00:00+00:00\"")),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            },
        ) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; isLenient = true })
            }
        }

        val preview = MaggieApiService(client).addMealGroceryItems(
            "m1",
            MealGroceryItemsRequest(listOf(MealGroceryItemChoice("veg"))),
        )

        assertEquals(HttpMethod.Post, capturedMethod)
        assertTrue(capturedUrl!!.endsWith("/api/meals/m1/grocery_items"))
        assertEquals("""{"ingredients":[{"ingredientId":"veg"}]}""", capturedBody)
        assertNotNull(preview.groceryChoiceMadeAt)
    }

    private fun categoryClient(onRequest: suspend (io.ktor.client.request.HttpRequestData) -> String): HttpClient =
        HttpClient(
            MockEngine { request ->
                respond(
                    content = ByteReadChannel(onRequest(request)),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/ld+json"),
                )
            },
        ) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; isLenient = true })
            }
        }

    @Test
    fun `updateCategory sends a merge-patch with the changed fields alone`() = runBlocking {
        var method: HttpMethod? = null
        var url: String? = null
        var contentType: String? = null
        var body: String? = null
        val service = MaggieApiService(
            categoryClient { request ->
                method = request.method
                url = request.url.toString()
                contentType = request.body.contentType?.toString()
                body = String(request.body.toByteArray())
                """{"id":"cat-1","name":"Courses alimentaires","obligation":"mandatory"}"""
            },
        )

        val updated = service.updateCategory(
            "cat-1",
            kotlinx.serialization.json.JsonObject(mapOf("name" to kotlinx.serialization.json.JsonPrimitive("Courses alimentaires"))),
        )

        assertEquals("Courses alimentaires", updated.name)
        assertEquals(HttpMethod.Patch, method)
        assertTrue(url!!.endsWith("/api/categories/cat-1"))
        assertTrue(contentType!!.startsWith("application/merge-patch+json"))
        assertEquals("""{"name":"Courses alimentaires"}""", body)
    }

    @Test
    fun `updateCategory sends an emptied field as an explicit null`() = runBlocking {
        var body: String? = null
        val service = MaggieApiService(
            categoryClient { request ->
                body = String(request.body.toByteArray())
                """{"id":"cat-1","name":"Courses"}"""
            },
        )

        service.updateCategory(
            "cat-1",
            kotlinx.serialization.json.JsonObject(mapOf("parent" to kotlinx.serialization.json.JsonNull)),
        )

        assertEquals("""{"parent":null}""", body)
    }

    @Test
    fun `countTransactionsOfCategory reads totalItems of a page of one`() = runBlocking {
        var url: io.ktor.http.Url? = null
        val service = MaggieApiService(
            categoryClient { request ->
                url = request.url
                """{"member":[],"totalItems":42}"""
            },
        )

        val count = service.countTransactionsOfCategory("cat-1")

        assertEquals(42, count)
        assertEquals("/api/categories/cat-1", url!!.parameters["category"])
        assertEquals("1", url!!.parameters["itemsPerPage"])
        assertTrue(url!!.encodedPath.endsWith("/api/transactions"))
    }

    @Test
    fun `countTransactionsOfCategory falls back to the page when there is no total`() = runBlocking {
        val service = MaggieApiService(categoryClient { """{"member":[]}""" })

        assertEquals(0, service.countTransactionsOfCategory("cat-1"))
    }
    @Test
    fun `countCategorizationRulesOf reads the category as the API spells it, an IRI`() = runBlocking {
        val service = MaggieApiService(
            categoryClient {
                """{"member":[
                    {"id":"r1","labelPattern":"LECLERC","category":"/api/categories/cat-1"},
                    {"id":"r2","labelPattern":"NETFLIX","category":"/api/categories/cat-2"},
                    {"id":"r3","labelPattern":"CARREFOUR","category":"/api/categories/cat-3"}
                ],"totalItems":3}"""
            },
        )

        assertEquals(2, service.countCategorizationRulesOf(setOf("cat-1", "cat-3")))
        assertEquals(0, service.countCategorizationRulesOf(setOf("cat-9")))
    }

    @Test
    fun `countCategorizationRulesOf gives no number when the page is not the whole list`() = runBlocking {
        val service = MaggieApiService(
            categoryClient {
                """{"member":[{"id":"r1","labelPattern":"LECLERC","category":"/api/categories/cat-1"}],"totalItems":31}"""
            },
        )

        assertNull(service.countCategorizationRulesOf(setOf("cat-1")))
    }
}

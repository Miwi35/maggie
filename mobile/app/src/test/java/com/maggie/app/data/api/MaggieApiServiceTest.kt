package com.maggie.app.data.api

import com.maggie.app.data.model.alreadyPlannedLabel
import com.maggie.app.data.model.packagesTotalLabel
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
import com.maggie.app.data.model.ChatMessage
import com.maggie.app.data.model.RecurringFrequency
import com.maggie.app.data.model.nextDueLabel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import com.maggie.app.data.model.RecurringSuggestion
import com.maggie.app.data.model.RulePreviewRequest

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
    fun `the stream request carries the screenshot as media type and base64 data`() = runBlocking {
        var capturedBody: String? = null
        val client = HttpClient(
            MockEngine { request ->
                capturedBody = (request.body as TextContent).text
                respond(content = ByteReadChannel(""), status = HttpStatusCode.OK)
            },
        ) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; isLenient = true }) }
        }

        MaggieApiService(client)
            .sendChatStream("c'est quoi ?", image = ChatImage.jpeg(byteArrayOf(1, 2, 3)))
            .collect {}

        assertTrue(capturedBody!!.contains("\"image\":{\"media_type\":\"image/jpeg\",\"data\":\"AQID\"}"))
    }

    @Test
    fun `a request without a screenshot has no image field at all`() {
        val body = Json.encodeToString(AgentChatRequest.serializer(), AgentChatRequest(message = "bonjour"))

        assertFalse(body.contains("image"))
    }

    @Test
    fun `ChatMessage reads hasImage, and defaults it to false when absent`() {
        val json = Json { ignoreUnknownKeys = true }

        assertTrue(json.decodeFromString<ChatMessage>("""{"id":"u-1","role":"user","content":"x","hasImage":true}""").hasImage)
        assertFalse(json.decodeFromString<ChatMessage>("""{"id":"u-1","role":"user","content":"x"}""").hasImage)
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

    private fun feedbackClient(status: HttpStatusCode, seen: MutableList<Triple<HttpMethod, String, String>>) = HttpClient(
        MockEngine { request ->
            seen += Triple(request.method, request.url.toString(), String(request.body.toByteArray()))
            respond(
                content = ByteReadChannel("""{"id":"a-1","role":"assistant","content":"x"}"""),
                status = status,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        },
    ) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; isLenient = true }) }
    }

    @Test
    fun `a thumb is put on the answer's feedback, with the rating and the comment in the body`() = runBlocking {
        val seen = mutableListOf<Triple<HttpMethod, String, String>>()

        MaggieApiService(feedbackClient(HttpStatusCode.OK, seen)).rateMessage("a-1", "down", "trop long")

        val (method, url, body) = seen.single()
        assertEquals(HttpMethod.Put, method)
        assertTrue(url.endsWith("/agent/messages/a-1/feedback"))
        assertTrue(body.contains("\"rating\":\"down\""))
        assertTrue(body.contains("\"comment\":\"trop long\""))
    }

    @Test
    fun `a thumb is taken back with a DELETE on the answer's feedback`() = runBlocking {
        val seen = mutableListOf<Triple<HttpMethod, String, String>>()

        MaggieApiService(feedbackClient(HttpStatusCode.OK, seen)).clearMessageFeedback("a-1")

        assertEquals(HttpMethod.Delete, seen.single().first)
        assertTrue(seen.single().second.endsWith("/agent/messages/a-1/feedback"))
    }

    @Test
    fun `a refused thumb is an error, so the chat does not show it as recorded`() = runBlocking {
        val client = feedbackClient(HttpStatusCode.NotFound, mutableListOf())

        val put = runCatching { MaggieApiService(client).rateMessage("a-1", "up") }.exceptionOrNull()
        val delete = runCatching { MaggieApiService(client).clearMessageFeedback("a-1") }.exceptionOrNull()

        assertTrue(put is IllegalStateException)
        assertTrue(delete is IllegalStateException)
    }

    @Test
    fun `ChatMessage reads the thumb and its comment, both absent on a message never rated`() {
        val json = Json { ignoreUnknownKeys = true }

        val rated = json.decodeFromString<ChatMessage>(
            """{"id":"a-1","role":"assistant","content":"x","feedback":"down","feedbackComment":"faux"}""",
        )
        val unrated = json.decodeFromString<ChatMessage>("""{"id":"a-1","role":"assistant","content":"x","feedback":null}""")

        assertEquals("down", rated.feedback)
        assertEquals("faux", rated.feedbackComment)
        assertNull(unrated.feedback)
        assertNull(unrated.feedbackComment)
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
        assertTrue(seen.single().second.endsWith("/agent/approvals/ap-1/approve?channel=chat"))
        assertEquals("approved", approval.status)
    }

    @Test
    fun `deny posts to the deny endpoint`() = runBlocking {
        val seen = mutableListOf<Pair<HttpMethod, String>>()
        val client = approvalClient(body = pendingJson.replace("pending", "denied"), seen = seen)

        val approval = MaggieApiService(client).deny("ap-1")

        assertTrue(seen.single().second.endsWith("/agent/approvals/ap-1/deny?channel=chat"))
        assertEquals("denied", approval.status)
    }

    @Test
    fun `an answer says where it was given, for the line the thread keeps`() = runBlocking {
        val seen = mutableListOf<Pair<HttpMethod, String>>()
        val client = approvalClient(body = pendingJson.replace("pending", "approved"), seen = seen)

        MaggieApiService(client).approve("ap-1", "voice")

        assertTrue(seen.single().second.endsWith("/agent/approvals/ap-1/approve?channel=voice"))
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
    fun `meals are asked for a whole month unpaginated, oldest first`() = runBlocking {
        val seen = mutableListOf<Pair<HttpMethod, String>>()
        val client = approvalClient(body = """{"member":[]}""", seen = seen)

        MaggieApiService(client).getMeals("2026-06-01", "2026-06-30")

        val url = seen.single().second
        assertTrue(url, url.contains("pagination=false"))
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
         "toBuy":{"quantity":1,"unit":"pack"},"converted":true,"stockState":"out","suggested":true,
         "onList":{"needed":[{"quantity":300,"unit":"g"}],"packages":1},
         "otherMeals":[{"mealId":"m0","name":"Bolognaise","date":"2026-10-15","quantity":300,"unit":"g"}],
         "withThisMeal":{"needed":[{"quantity":600,"unit":"g"}],"packagesTotal":2,"packagesToAdd":1}},
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
        // MAG-380: what is already planned, and the packagings once this meal is added.
        assertEquals("déjà prévu : 300 g (Bolognaise, jeu.)", preview.ingredients[0].alreadyPlannedLabel())
        assertEquals("2 paquets au total (+1)", preview.ingredients[0].packagesTotalLabel())
        // An older payload without the new fields still decodes.
        assertNull(preview.ingredients[1].alreadyPlannedLabel())
        assertNull(preview.ingredients[1].packagesTotalLabel())
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
    fun `getCategories asks for the whole tree, not the first page of thirty`() = runBlocking {
        var url: String? = null
        val service = MaggieApiService(
            categoryClient { request ->
                url = request.url.toString()
                """{"member":[{"id":"cat-1","name":"Loisirs","obligation":"optional"}]}"""
            },
        )

        val categories = service.getCategories()

        assertEquals(1, categories.size)
        assertTrue(url!!.contains("pagination=false"))
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

    private fun refusingClient(status: HttpStatusCode, body: String): HttpClient =
        HttpClient(
            MockEngine {
                respond(
                    content = ByteReadChannel(body),
                    status = status,
                    headers = headersOf(HttpHeaders.ContentType, "application/problem+json"),
                )
            },
        ) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; isLenient = true })
            }
        }

    @Test
    fun `a category refused with 422 raises the server's own sentence`() = runBlocking {
        val sentence = "Une catégorie ne peut pas dépasser trois niveaux."
        val service = MaggieApiService(refusingClient(HttpStatusCode.UnprocessableEntity, """{"detail":"$sentence"}"""))

        val created = runCatching { service.createCategory(CategoryCreateRequest(name = "Trop")) }.exceptionOrNull()
        val moved = runCatching {
            service.updateCategory("cat-1", kotlinx.serialization.json.JsonObject(emptyMap()))
        }.exceptionOrNull()

        assertTrue(created is ApiRefusalException)
        assertEquals(422, (created as ApiRefusalException).status)
        assertEquals(sentence, created.message)
        assertEquals(sentence, (moved as ApiRefusalException).message)
    }

    @Test
    fun `a refusal reads hydra description when there is no detail`() {
        assertEquals("Cycle.", refusalMessage(422, """{"hydra:description":"Cycle."}"""))
        assertEquals("Le serveur a refusé la modification (HTTP 422).", refusalMessage(422, "<html>"))
    }

    @Test
    fun `updateTransactionCategory patches the category by IRI, or null to take it away`() = runBlocking {
        val bodies = mutableListOf<String>()
        var url: String? = null
        val service = MaggieApiService(
            categoryClient { request ->
                url = request.url.toString()
                bodies += String(request.body.toByteArray())
                """{"id":"tx-1","label":"Bar","amountCents":-500,"category":"/api/categories/cat-9"}"""
            },
        )

        val updated = service.updateTransactionCategory("tx-1", "/api/categories/cat-9")
        service.updateTransactionCategory("tx-1", null)

        assertEquals("cat-9", updated.categoryId)
        assertTrue(url!!.endsWith("/api/transactions/tx-1"))
        assertEquals("""{"category":"/api/categories/cat-9"}""", bodies[0])
        assertEquals("""{"category":null}""", bodies[1])
    }

    private val rentRequest = RecurringOperationWriteRequest(
        label = "Loyer",
        counterpartyName = "SCI VERDA",
        labelPattern = null,
        account = "/api/accounts/acc-1",
        category = "/api/categories/cat-1",
        period = "monthly",
        dayRule = "fixed_day",
        anchorOn = "2026-04-05",
        referenceAmountCents = -90000,
        referenceSource = "declared",
        amountTolerancePercent = 20,
        dateToleranceDays = 5,
        endsOn = null,
    )

    @Test
    fun `getRecurringOperationsOverview reads the series, the proposals and the totals`() = runBlocking {
        var url: String? = null
        val service = MaggieApiService(
            categoryClient { request ->
                url = request.url.toString()
                """{"operations":[{"id":"s1","label":"Loyer","monthlyCostCents":-90000,"yearlyCostCents":-1080000,
                    "missedOccurrence":{"occurrenceOn":"2026-04-05","daysOverdue":17}}],
                    "proposals":[{"transactionId":"t1","label":"LOYER","reason":"late","recurringOperationId":"s1"}],
                    "totals":{"monthlyCostCents":90000,"yearlyCostCents":1080000,"monthlyIncomeCents":0,"activeCount":1}}"""
            },
        )

        val overview = service.getRecurringOperationsOverview()

        assertTrue(url!!.endsWith("/api/finance/recurring-operations/overview"))
        assertEquals(-90000, overview.operations.single().monthlyCostCents)
        assertEquals(17, overview.operations.single().missedOccurrence?.daysOverdue)
        assertEquals("late", overview.proposals.single().reason)
        assertEquals(1, overview.totals.activeCount)
    }

    @Test
    fun `getRecurringOperationSuggestions unwraps the suggestions`() = runBlocking {
        val service = MaggieApiService(
            categoryClient {
                """{"suggestions":[{"label":"Netflix","accountId":"acc-1","period":"monthly",
                    "anchorOn":"2026-03-14","dayRule":"fixed_day","referenceAmountCents":-1349}]}"""
            },
        )

        assertEquals("Netflix", service.getRecurringOperationSuggestions().single().label)
    }

    @Test
    fun `acceptRecurringOperationSuggestions posts the suggestions with the fields accept requires`() = runBlocking {
        var method: HttpMethod? = null
        var url: String? = null
        var body: String? = null
        val service = MaggieApiService(
            categoryClient { request ->
                method = request.method
                url = request.url.toString()
                body = String(request.body.toByteArray())
                """{"created":1,"labels":["Netflix"],"attached":3,"proposed":0}"""
            },
        )

        val result = service.acceptRecurringOperationSuggestions(
            listOf(
                RecurringSuggestion(
                    label = "Netflix",
                    accountId = "acc-1",
                    period = "monthly",
                    anchorOn = "2026-03-14",
                    dayRule = "fixed_day",
                    referenceAmountCents = -1349,
                    categoryId = "cat-1",
                ),
            ),
        )

        assertEquals(HttpMethod.Post, method)
        assertTrue(url!!.endsWith("/api/finance/recurring-operations/suggestions/accept"))
        assertTrue(body!!.startsWith("""{"suggestions":[{"label":"Netflix""""))
        assertTrue(body!!.contains(""""period":"monthly""""))
        assertTrue(body!!.contains(""""dayRule":"fixed_day""""))
        assertTrue(body!!.contains(""""categoryId":"cat-1""""))
        assertEquals(3, result.attached)
    }

    @Test
    fun `attachRecurringOperations sends dryRun even when it is false`() = runBlocking {
        val bodies = mutableListOf<String>()
        val service = MaggieApiService(
            categoryClient { request ->
                bodies += String(request.body.toByteArray())
                """{"dryRun":true,"scanned":12,"attached":3,"proposed":1,"attachments":[],"proposals":[]}"""
            },
        )

        val report = service.attachRecurringOperations(dryRun = true)
        service.attachRecurringOperations(dryRun = false)

        assertEquals(listOf("""{"dryRun":true}""", """{"dryRun":false}"""), bodies.map { it.replace(" ", "") })
        assertEquals(3, report.attached)
    }

    @Test
    fun `createRecurringOperation posts the whole series, nulls included`() = runBlocking {
        var method: HttpMethod? = null
        var url: String? = null
        var body: String? = null
        val service = MaggieApiService(
            categoryClient { request ->
                method = request.method
                url = request.url.toString()
                body = String(request.body.toByteArray())
                """{"id":"s1","label":"Loyer","referenceAmountCents":-90000}"""
            },
        )

        val created = service.createRecurringOperation(rentRequest)

        assertEquals("s1", created.id)
        assertEquals(HttpMethod.Post, method)
        assertTrue(url!!.endsWith("/api/recurring_operations"))
        assertTrue(body!!.contains(""""account":"/api/accounts/acc-1""""))
        assertTrue(body!!.contains(""""category":"/api/categories/cat-1""""))
        assertTrue(body!!.contains(""""referenceAmountCents":-90000"""))
        assertTrue(body!!.contains(""""referenceSource":"declared""""))
        assertTrue(body!!.contains(""""amountTolerancePercent":20"""))
        assertTrue(body!!.contains(""""dateToleranceDays":5"""))
        assertTrue(body!!.contains(""""endsOn":null"""))
    }

    @Test
    fun `updateRecurringOperation sends a merge-patch that can clear a field`() = runBlocking {
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
                """{"id":"s1","label":"Loyer"}"""
            },
        )

        service.updateRecurringOperation("s1", rentRequest.copy(endsOn = null, labelPattern = null))

        assertEquals(HttpMethod.Patch, method)
        assertTrue(url!!.endsWith("/api/recurring_operations/s1"))
        assertTrue(contentType!!.startsWith("application/merge-patch+json"))
        assertTrue(body!!.contains(""""labelPattern":null"""))
        assertTrue(body!!.contains(""""endsOn":null"""))
    }

    @Test
    fun `deleteRecurringOperation deletes the series by id`() = runBlocking {
        var method: HttpMethod? = null
        var url: String? = null
        val service = MaggieApiService(
            categoryClient { request ->
                method = request.method
                url = request.url.toString()
                ""
            },
        )

        service.deleteRecurringOperation("s1")

        assertEquals(HttpMethod.Delete, method)
        assertTrue(url!!.endsWith("/api/recurring_operations/s1"))
    }

    @Test
    fun `setTransactionRecurrence attaches with the series IRI and the due date`() = runBlocking {
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
                "{}"
            },
        )

        service.setTransactionRecurrence("t1", "s1", "2026-04-05")

        assertEquals(HttpMethod.Patch, method)
        assertTrue(url!!.endsWith("/api/transactions/t1"))
        assertTrue(contentType!!.startsWith("application/merge-patch+json"))
        assertEquals("""{"recurringOperation":"/api/recurring_operations/s1","recurringOccurrenceOn":"2026-04-05"}""", body)
    }

    @Test
    fun `setTransactionRecurrence detaches with two explicit nulls`() = runBlocking {
        var body: String? = null
        val service = MaggieApiService(
            categoryClient { request ->
                body = String(request.body.toByteArray())
                "{}"
            },
        )

        service.setTransactionRecurrence("t1", null, null)

        assertEquals("""{"recurringOperation":null,"recurringOccurrenceOn":null}""", body)
    }

    private val outlookJson = """
        {"today":"2026-10-13",
         "salary":{"isConfigured":true,"expectedOn":"2026-10-10","referenceCents":250000,"isReceived":false,"isLate":true,"delayDays":3,"shortfallCents":0},
         "balanceCents":-12000,
         "projection":[{"date":"2026-10-13","balanceCents":-12000,"movements":[{"id":"m1","label":"Loyer","amountCents":-60000,"status":"planned","accountId":"a1","accountName":"Courant"}]}],
         "balanceBeforeSalary":{"onDate":"2026-10-24","balanceCents":-70000},
         "overdraft":{"isForecast":true,"onDate":"2026-10-14","worstBalanceCents":-70000,"limitCents":0},
         "advice":{"amountCents":70000,"sourceAccountId":"s1","sourceAccountName":"Livret A","sourceBalanceCents":50000,"sourceAmountCents":50000,"isCushion":false,
                   "alternatives":[{"accountId":"s2","accountName":"Matelas","balanceCents":30000,"amountCents":20000,"isCushion":true}],"uncoveredCents":0,"touchesCushion":true},
         "outstandingAdvancesCents":10000}
    """.trimIndent()

    @Test
    fun `getTreasuryOutlook reads the outlook of the treasury endpoint`() = runBlocking {
        var url: String? = null
        val service = MaggieApiService(
            categoryClient { request ->
                url = request.url.toString()
                outlookJson
            },
        )

        val outlook = service.getTreasuryOutlook()

        assertTrue(url!!.endsWith("/api/finance/treasury-outlook"))
        assertEquals(3, outlook.salary.delayDays)
        assertTrue(outlook.salary.isLate)
        assertEquals("2026-10-14", outlook.overdraft.onDate)
        assertEquals("s1", outlook.advice?.sourceAccountId)
        assertTrue(outlook.advice!!.touchesCushion)
        assertEquals(listOf(true), outlook.advice!!.alternatives.map { it.isCushion })
        assertEquals("Loyer", outlook.projection.single().movements.single().label)
        assertEquals(10000, outlook.outstandingAdvancesCents)
    }

    @Test
    fun `an outlook not yet configured reads with its nulls`() = runBlocking {
        val service = MaggieApiService(
            categoryClient {
                """{"today":"2026-10-13","salary":{"isConfigured":false,"expectedOn":null,"referenceCents":null,"isReceived":false,"isLate":false,"delayDays":0,"shortfallCents":0},"balanceCents":0,"projection":[],"balanceBeforeSalary":null,"overdraft":{"isForecast":false,"onDate":null,"worstBalanceCents":0,"limitCents":0},"advice":null,"outstandingAdvancesCents":0}"""
            },
        )

        val outlook = service.getTreasuryOutlook()

        assertFalse(outlook.salary.isConfigured)
        assertNull(outlook.salary.expectedOn)
        assertNull(outlook.balanceBeforeSalary)
        assertNull(outlook.advice)
    }

    @Test
    fun `updateTreasurySettings patches only the field it was given`() = runBlocking {
        var method: HttpMethod? = null
        var url: String? = null
        val bodies = mutableListOf<String>()
        val service = MaggieApiService(
            categoryClient { request ->
                method = request.method
                url = request.url.toString()
                bodies += String(request.body.toByteArray())
                """{"success":true,"salaryExpectedDay":null,"overdraftLimitCents":25000}"""
            },
        )

        val settings = service.updateTreasurySettings(TreasurySettingsRequest(overdraftLimitCents = 25000))
        service.updateTreasurySettings(TreasurySettingsRequest(salaryExpectedDay = 28))

        assertEquals(HttpMethod.Patch, method)
        assertTrue(url!!.endsWith("/api/finance/treasury-settings"))
        assertEquals(listOf("""{"overdraftLimitCents":25000}""", """{"salaryExpectedDay":28}"""), bodies)
        assertEquals(25000, settings.overdraftLimitCents)
        assertNull(settings.salaryExpectedDay)
    }

    @Test
    fun `a settings request refused with 400 raises the server's own message`() = runBlocking {
        val sentence = "overdraftLimitCents must be an integer of 0 or more cents"
        val service = MaggieApiService(refusingClient(HttpStatusCode.BadRequest, """{"error":"$sentence"}"""))

        val refused = runCatching {
            service.updateTreasurySettings(TreasurySettingsRequest(overdraftLimitCents = -1))
        }.exceptionOrNull()

        assertTrue(refused is ApiRefusalException)
        assertEquals(400, (refused as ApiRefusalException).status)
        assertEquals(sentence, refused.message)
    }

    @Test
    fun `an outlook the server could not compute is a failure, not an empty outlook`() = runBlocking {
        val service = MaggieApiService(refusingClient(HttpStatusCode.InternalServerError, """{"error":"boom"}"""))

        val failed = runCatching { service.getTreasuryOutlook() }.exceptionOrNull()

        assertTrue(failed is ApiRefusalException)
    }

    @Test
    fun `updateTransactionNote patches the note, and null erases it`() = runBlocking {
        val bodies = mutableListOf<String>()
        var url: String? = null
        var contentType: String? = null
        val service = MaggieApiService(
            categoryClient { request ->
                url = request.url.toString()
                contentType = request.body.contentType?.toString()
                bodies += String(request.body.toByteArray())
                """{"id":"t1","label":"Cadeau","note":"Pour Léa"}"""
            },
        )

        val updated = service.updateTransactionNote("t1", "Pour Léa")
        service.updateTransactionNote("t1", null)

        assertTrue(url!!.endsWith("/api/transactions/t1"))
        assertTrue(contentType!!.startsWith("application/merge-patch+json"))
        assertEquals("Pour Léa", updated.note)
        assertEquals(listOf("""{"note":"Pour Léa"}""", """{"note":null}"""), bodies)
    }

    @Test
    fun `previewCategorizationRule posts the criteria and reads the lines caught`() = runBlocking {
        var method: HttpMethod? = null
        var url: String? = null
        var body: String? = null
        val service = MaggieApiService(
            categoryClient { request ->
                method = request.method
                url = request.url.toString()
                body = String(request.body.toByteArray())
                """{"total":3,"changeCount":1,"matches":[{"transactionId":"t1","label":"CARREFOUR","amountCents":-4250,""" +
                    """"bookedAt":"2026-03-17T00:00:00+00:00","currentCategoryId":null,"wouldChange":true}]}"""
            },
        )

        val preview = service.previewCategorizationRule(
            RulePreviewRequest(labelPattern = "CARREFOUR", direction = "debit", categoryId = "c1"),
        )

        assertEquals(HttpMethod.Post, method)
        assertTrue(url!!.endsWith("/api/finance/categorization-rules/preview"))
        assertEquals("""{"labelPattern":"CARREFOUR","direction":"debit","categoryId":"c1"}""", body)
        assertEquals(3, preview.total)
        assertEquals(1, preview.changeCount)
        assertEquals("CARREFOUR", preview.matches.single().label)
        assertTrue(preview.matches.single().wouldChange)
    }

    @Test
    fun `the recurring items are read without pagination, with what the tab displays`() = runBlocking {
        var url: io.ktor.http.Url? = null
        val service = MaggieApiService(
            categoryClient { request ->
                url = request.url
                """{"member":[{"id":"r1","product":"/api/products/p1","label":"Lait","frequency":"weekly","nextDueAt":"2026-10-20"}]}"""
            },
        )

        val items = service.getRecurringGroceryItems()

        assertEquals("false", url!!.parameters["pagination"])
        assertTrue(url!!.encodedPath.endsWith("/api/recurring_grocery_items"))
        assertEquals("p1", items.single().productId)
        assertEquals("Lait", items.single().label)
        assertEquals("20/10/2026", items.single().nextDueLabel())
    }

    @Test
    fun `a recurring item's frequency is changed by a merge-patch of that field alone`() = runBlocking {
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
                """{"id":"r1","frequency":"biweekly"}"""
            },
        )

        val updated = service.updateRecurringGroceryItemFrequency("r1", RecurringFrequency.BIWEEKLY)

        assertEquals(RecurringFrequency.BIWEEKLY, updated.frequency)
        assertEquals(HttpMethod.Patch, method)
        assertTrue(url!!.endsWith("/api/recurring_grocery_items/r1"))
        assertTrue(contentType!!.startsWith("application/merge-patch+json"))
        assertEquals("""{"frequency":"biweekly"}""", body)
    }
}

package com.maggie.app.data.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * API Platform serves a collection 30 at a time unless `pagination=false` asks for all of it. The
 * app read the first page and stopped there: seven events of the 37 in range never reached the
 * agenda (MAG-430). A fake server with that exact behaviour stands in for the API: the lists the
 * app displays in full must ask for everything in one request, the infinite-scroll one must not.
 */
class UnpaginatedReadsTest {

    private val pageSize = 30

    /** [total] items of [item], paginated by 30 with a `view.next` unless `pagination=false` is sent. */
    private class FakeCollection(
        private val total: Int,
        private val pageSize: Int,
        private val item: (Int) -> String,
    ) {
        val requests = mutableListOf<Url>()

        fun client() = HttpClient(
            MockEngine { request ->
                requests += request.url
                val unpaginated = request.url.parameters["pagination"] == "false"
                val page = request.url.parameters["page"]?.toInt() ?: 1
                val from = if (unpaginated) 0 else (page - 1) * pageSize
                val to = if (unpaginated) total else minOf(from + pageSize, total)
                val members = (from until to).joinToString(",") { item(it) }
                val view = if (!unpaginated && to < total) {
                    ""","view":{"@id":"x","next":"${request.url.encodedPath}?page=${page + 1}"}"""
                } else {
                    ""
                }
                respond(
                    content = ByteReadChannel("""{"member":[$members],"totalItems":$total$view}"""),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/ld+json"),
                )
            },
        ) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; isLenient = true }) }
        }
    }

    private fun events(total: Int) = FakeCollection(total, pageSize, { """{"id":"e$it","summary":"Événement $it"}""" })

    private fun FakeCollection.single(): Url {
        assertEquals("requests: $requests", 1, requests.size)
        return requests.single()
    }

    @Test
    fun `getEvents reads the 37 events of the period in one request`() = runBlocking {
        val server = events(37)

        val result = MaggieApiService(server.client())
            .getEvents("2026-10-01T00:00:00+02:00", "2026-11-01T00:00:00+01:00")

        assertEquals(37, result.size)
        assertEquals("e36", result.last().id)
        val url = server.single()
        assertEquals("false", url.parameters["pagination"])
    }

    @Test
    fun `getEvents asks for the events that overlap the period`() = runBlocking {
        val server = events(3)

        MaggieApiService(server.client()).getEvents("2026-10-01T00:00:00+02:00", "2026-11-01T00:00:00+01:00")

        val url = server.single()
        assertEquals("2026-10-01T00:00:00+02:00", url.parameters["endAt[after]"])
        assertEquals("2026-11-01T00:00:00+01:00", url.parameters["startAt[before]"])
        assertNull(url.parameters["startAt[after]"])
        assertNull(url.parameters["endAt[before]"])
    }

    @Test
    fun `getRecurringEventsBefore reads the 37 series in one request`() = runBlocking {
        val server = events(37)

        val result = MaggieApiService(server.client()).getRecurringEventsBefore("2027-10-13T00:00:00+02:00")

        assertEquals(37, result.size)
        val url = server.single()
        assertEquals("false", url.parameters["pagination"])
        assertEquals("true", url.parameters["exists[rrule]"])
        assertEquals("2027-10-13T00:00:00+02:00", url.parameters["startAt[strictly_before]"])
    }

    @Test
    fun `a task list, an agenda list, a product list and a month of meals come in one request`() = runBlocking {
        fun <T> check(read: suspend (MaggieApiService) -> List<T>, item: (Int) -> String) = runBlocking {
            val server = FakeCollection(35, pageSize, item)
            assertEquals(35, read(MaggieApiService(server.client())).size)
            assertEquals("false", server.single().parameters["pagination"])
        }

        check({ it.getTasks() }) { """{"id":"t$it","title":"Tâche $it"}""" }
        check({ it.getUndoneTasks("2026-10-13") }) { """{"id":"t$it","title":"Tâche $it"}""" }
        check({ it.getAgendas() }) { """{"id":"a$it","name":"Agenda $it"}""" }
        check({ it.getProducts() }) { """{"id":"p$it","name":"Produit $it","category":"produce"}""" }
        check({ it.getMeals("2026-10-01", "2026-10-31") }) {
            """{"id":"m$it","summary":"Repas $it","date":"2026-10-13","slot":"lunch"}"""
        }
    }

    @Test
    fun `an empty collection is an empty list`() = runBlocking {
        assertEquals(0, MaggieApiService(events(0).client()).getEvents("2026-10-01T00:00:00Z", "2026-11-01T00:00:00Z").size)
    }

    @Test
    fun `getTransactions keeps its pagination, the screen scrolls without end`() = runBlocking {
        val server = FakeCollection(370, pageSize, { """{"id":"tx$it","label":"Ligne $it"}""" })

        val result = MaggieApiService(server.client()).getTransactions("acc-1")

        assertEquals(30, result.size)
        val url = server.single()
        assertFalse(url.toString(), url.parameters.contains("pagination"))
        assertEquals("/api/accounts/acc-1", url.parameters["account"])
    }
}

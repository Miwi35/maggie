package com.maggie.app.data.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * API Platform serves a collection 30 at a time (`member`, `totalItems`, `view.next`). The app
 * read the first page and stopped there: three events of the 37 in range never reached the
 * agenda (MAG-430). A fake server with that exact pagination stands in for the API.
 */
class PaginatedReadsTest {

    private val pageSize = 30

    /** Serves [total] items of [item] by pages of 30, the next page named by `view.next` as API Platform spells it. */
    private class FakeCollection(
        private val total: Int,
        private val pageSize: Int,
        private val item: (Int) -> String,
        private val alwaysNext: Boolean = false,
    ) {
        val requests = mutableListOf<String>()

        fun client() = HttpClient(
            MockEngine { request ->
                requests += request.url.toString()
                val page = request.url.parameters["page"]?.toInt() ?: 1
                val from = (page - 1) * pageSize
                val members = (from until minOf(from + pageSize, total)).joinToString(",") { item(it) }
                val hasNext = alwaysNext || from + pageSize < total
                val query = request.url.encodedQuery
                    .split("&")
                    .filter { it.isNotEmpty() && !it.startsWith("page=") }
                    .plus("page=${page + 1}")
                    .joinToString("&")
                val view = if (hasNext) ""","view":{"@id":"x","next":"${request.url.encodedPath}?$query"}""" else ""
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

    private fun tasks(total: Int) = FakeCollection(total, pageSize, { """{"id":"t$it","title":"Tâche $it"}""" })

    @Test
    fun `getEvents reads every page, not the first thirty`() = runBlocking {
        val server = events(37)

        val result = MaggieApiService(server.client()).getEvents("2026-07-13", "2027-10-13")

        assertEquals(37, result.size)
        assertEquals("e36", result.last().id)
        assertEquals(2, server.requests.size)
    }

    @Test
    fun `getEvents keeps its filters on the pages after the first`() = runBlocking {
        val server = events(37)

        MaggieApiService(server.client()).getEvents("2026-07-13", "2027-10-13")

        val second = server.requests[1]
        assertTrue(second, second.contains("page=2"))
        assertTrue(second, second.contains("startAt%5Bafter%5D=2026-07-13") || second.contains("startAt[after]=2026-07-13"))
        assertTrue(second, second.contains("endAt%5Bbefore%5D=2027-10-13") || second.contains("endAt[before]=2027-10-13"))
    }

    @Test
    fun `getRecurringEventsBefore reads every page`() = runBlocking {
        val server = events(37)

        val result = MaggieApiService(server.client()).getRecurringEventsBefore("2027-10-13")

        assertEquals(37, result.size)
    }

    @Test
    fun `getTasks reads every page`() = runBlocking {
        val server = tasks(57)

        val result = MaggieApiService(server.client()).getTasks()

        assertEquals(57, result.size)
        assertEquals(2, server.requests.size)
    }

    @Test
    fun `the undone task lists read every page`() = runBlocking {
        assertEquals(40, MaggieApiService(tasks(40).client()).getUndoneTasks("2026-10-13").size)
        assertEquals(40, MaggieApiService(tasks(40).client()).getUndoneUndatedTasks().size)
    }

    @Test
    fun `getTransactions reads every page`() = runBlocking {
        val server = FakeCollection(370, pageSize, { """{"id":"tx$it","label":"Ligne $it"}""" })

        val result = MaggieApiService(server.client()).getTransactions("acc-1")

        assertEquals(370, result.size)
        assertEquals(13, server.requests.size)
        assertEquals(370, result.map { it.id }.toSet().size)
    }

    @Test
    fun `a collection that fits one page costs one request`() = runBlocking {
        val server = events(12)

        val result = MaggieApiService(server.client()).getEvents()

        assertEquals(12, result.size)
        assertEquals(1, server.requests.size)
    }

    @Test
    fun `an empty collection is an empty list`() = runBlocking {
        assertEquals(0, MaggieApiService(events(0).client()).getEvents().size)
    }

    @Test
    fun `a server that never stops saying there is a next page does not trap the app`() = runBlocking {
        val server = FakeCollection(10, pageSize, { """{"id":"e$it","summary":"x"}""" }, alwaysNext = true)

        val result = MaggieApiService(server.client()).getEvents()

        assertTrue("stopped after ${server.requests.size} requests", server.requests.size <= 100)
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun `the other collections read every page too`() = runBlocking {
        fun <T> check(read: suspend (MaggieApiService) -> List<T>, item: (Int) -> String) = runBlocking {
            val server = FakeCollection(35, pageSize, item)
            assertEquals(35, read(MaggieApiService(server.client())).size)
        }

        check({ it.getAgendas() }) { """{"id":"a$it","name":"Agenda $it"}""" }
        check({ it.getProducts() }) { """{"id":"p$it","name":"Produit $it","category":"produce"}""" }
        check({ it.getRecipes() }) { """{"id":"r$it","name":"Recette $it"}""" }
        check({ it.getMeals() }) { """{"id":"m$it","summary":"Repas $it","date":"2026-10-13","slot":"lunch"}""" }
        check({ it.getNotifications() }) { """{"id":"n$it","title":"Notification $it"}""" }
    }
}

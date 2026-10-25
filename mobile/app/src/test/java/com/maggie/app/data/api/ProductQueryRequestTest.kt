package com.maggie.app.data.api

import com.maggie.app.data.model.AisleFilter
import com.maggie.app.data.model.ProductQuery
import com.maggie.app.data.model.ProductSort
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The filters and sort of the products list go to the API as its own query parameters (MAG-604, MAG-606). */
class ProductQueryRequestTest {

    private fun read(query: ProductQuery?): Url {
        var sent: Url? = null
        val client = HttpClient(
            MockEngine { request ->
                sent = request.url
                respond(
                    content = """{"member":[],"totalItems":0}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/ld+json"),
                )
            },
        ) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; isLenient = true }) }
        }
        val service = MaggieApiService(client)
        runBlocking { if (query == null) service.getProducts() else service.getProducts(query) }
        return sent!!
    }

    @Test
    fun `no filter asks for every product by name, in one request`() {
        val url = read(null)

        assertEquals("/api/products", url.encodedPath)
        assertEquals("false", url.parameters["pagination"])
        assertEquals("asc", url.parameters["order[name]"])
        assertNull(url.parameters["store"])
        assertNull(url.parameters["aisle"])
    }

    @Test
    fun `a store is sent as its IRI`() {
        val url = read(ProductQuery(storeId = "s1"))

        assertEquals("/api/stores/s1", url.parameters["store"])
        assertNull(url.parameters["aisle"])
    }

    @Test
    fun `an aisle is sent as its IRI, and Sans rayon as none`() {
        assertEquals("/api/aisles/a1", read(ProductQuery("s1", AisleFilter.One("a1"))).parameters["aisle"])
        assertEquals("none", read(ProductQuery("s1", AisleFilter.None)).parameters["aisle"])
    }

    @Test
    fun `each sort is the API's order field`() {
        val url = read(ProductQuery(storeId = "s1", sort = ProductSort.AISLE))
        assertEquals("asc", url.parameters["order[aisle]"])
        assertNull(url.parameters["order[name]"])

        assertEquals("asc", read(ProductQuery(sort = ProductSort.STOCK)).parameters["order[stockState]"])
    }

    @Test
    fun `an aisle or an aisle sort without a store is never sent, the API answers 400 to them`() {
        val url = read(ProductQuery(storeId = null, aisle = AisleFilter.One("a1"), sort = ProductSort.AISLE))

        assertNull(url.parameters["aisle"])
        assertNull(url.parameters["order[aisle]"])
        assertEquals("asc", url.parameters["order[name]"])
    }
}

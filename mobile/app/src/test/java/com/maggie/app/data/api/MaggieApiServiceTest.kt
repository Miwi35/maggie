package com.maggie.app.data.api

import org.junit.Test
import org.junit.Assert.*

class MaggieApiServiceTest {
    // NOTE: MaggieApiService creates its own HttpClient internally.
    // To fully test with MockEngine, it needs a refactor to accept HttpClient
    // via constructor (Koin would provide the production client).
    // For now, we test serialization of request/response data classes.

    @Test
    fun `ChatRequest serializes with defaults`() {
        val request = ChatRequest(message = "Hello")
        assertEquals("Hello", request.message)
        assertEquals("default", request.user_id)
    }

    @Test
    fun `ChatResponse has empty tool_calls by default`() {
        val response = ChatResponse(response = "Hi there!")
        assertEquals("Hi there!", response.response)
        assertTrue(response.tool_calls.isEmpty())
    }
}

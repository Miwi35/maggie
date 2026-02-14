package com.maggie.app.data.mercure

import io.ktor.http.Url
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MercureServiceTest {

    @Test
    fun `buildSubscriptionUrl encodes topic parameter`() {
        val url = MercureService.buildSubscriptionUrl(
            hubUrl = "http://maggie.local/.well-known/mercure",
            topic = "/api/events/{id}"
        )

        assertTrue(url.startsWith("http://maggie.local/.well-known/mercure?"))
        val parsed = Url(url)
        assertEquals("/api/events/{id}", parsed.parameters["topic"])
    }

    @Test
    fun `buildSubscriptionUrl handles simple topic`() {
        val url = MercureService.buildSubscriptionUrl(
            hubUrl = "http://localhost/.well-known/mercure",
            topic = "/agent/chat/default"
        )

        val parsed = Url(url)
        assertEquals("/agent/chat/default", parsed.parameters["topic"])
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

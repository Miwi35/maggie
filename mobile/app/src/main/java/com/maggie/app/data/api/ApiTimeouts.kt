package com.maggie.app.data.api

import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder

/**
 * Without this plugin OkHttp's own 10 s read timeout cut every call, so an answer
 * from the agent — a tool call and an MCP reconnection are enough to pass it — was
 * lost to the screen and to the voice while it sat in the database (MAG-319).
 * The 10 s stay the rule; only the agent's replies get more.
 */
object ApiTimeouts {
    const val CONNECT_MS = 10_000L
    const val DEFAULT_MS = 10_000L

    /** `/agent/chat`: nothing comes back until Maggie has finished, tools included. */
    const val CHAT_MS = 120_000L

    /** `/agent/chat/stream`: the longest silence between two events. */
    const val STREAM_SILENCE_MS = 90_000L
    const val STREAM_TOTAL_MS = 300_000L
}

fun HttpClientConfig<*>.installApiTimeouts() {
    install(HttpTimeout) {
        connectTimeoutMillis = ApiTimeouts.CONNECT_MS
        socketTimeoutMillis = ApiTimeouts.DEFAULT_MS
    }
}

fun HttpRequestBuilder.waitForAgentReply() {
    timeout {
        requestTimeoutMillis = ApiTimeouts.CHAT_MS
        socketTimeoutMillis = ApiTimeouts.CHAT_MS
    }
}

fun HttpRequestBuilder.waitForAgentStream() {
    timeout {
        requestTimeoutMillis = ApiTimeouts.STREAM_TOTAL_MS
        socketTimeoutMillis = ApiTimeouts.STREAM_SILENCE_MS
    }
}

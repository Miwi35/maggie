package com.maggie.app.data.api

import io.ktor.client.HttpClientConfig

/**
 * Nothing in dev and prod: the journey identity is an e2e header. Its counterpart, which
 * adds `X-E2E-Journey` to every request, lives in `src/e2e/`.
 */
fun HttpClientConfig<*>.installJourneyHeader() = Unit

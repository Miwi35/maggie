package com.maggie.app.data.api

import com.maggie.app.e2e.E2eJourney
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.api.createClientPlugin

/**
 * Every request of the e2e app says which Maestro flow made it (« Sélection e2e par
 * couverture »): `X-E2E-Journey: e2e/mobile/flows/01-login-chat.yaml`. The API and the
 * agent file the lines they ran under that journey, which is how a PR learns which
 * flows its change can break.
 *
 * Read at each request, not at install: the client is built before the first activity,
 * which is where the id arrives ([E2eJourney]). No id, no header. Compiled into the `e2e`
 * flavor alone — its counterpart in `src/google/` installs nothing.
 */
val JourneyHeaderPlugin = createClientPlugin("E2eJourneyHeader") {
    onRequest { request, _ ->
        E2eJourney.id?.let { request.headers[E2eJourney.HEADER] = it }
    }
}

fun HttpClientConfig<*>.installJourneyHeader() {
    install(JourneyHeaderPlugin)
}

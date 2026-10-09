package com.maggie.app.e2e

import android.app.Activity
import android.content.Intent
import com.maggie.app.data.api.installJourneyHeader
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.get
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * The journey identity of the e2e app (« Sélection e2e par couverture »): the flow's
 * path arrives as a launch extra, and every request then carries it as `X-E2E-Journey`.
 * In `src/testE2e/`, like the code it tests: neither exists in dev or prod.
 */
class JourneyHeaderTest {

    @Before
    fun clear() = E2eJourney.reset()

    @After
    fun tearDown() = E2eJourney.reset()

    private fun intentNaming(journey: String?): Intent = mockk {
        every { getStringExtra(E2eJourney.EXTRA) } returns journey
    }

    private fun requestThroughTheApp(): HttpRequestData {
        val seen = mutableListOf<HttpRequestData>()
        val client = HttpClient(MockEngine { request -> seen += request; respondOk() }) {
            installJourneyHeader()
        }
        runBlocking { client.get("http://localhost:8099/api/events") }
        return seen.single()
    }

    @Test
    fun `no journey, no header`() {
        assertNull(requestThroughTheApp().headers[E2eJourney.HEADER])
    }

    @Test
    fun `an activity launched by a flow names the journey on every request after it`() {
        val activity = mockk<Activity> { every { intent } returns intentNaming("e2e/mobile/flows/01-login-chat.yaml") }

        E2eHooks.JourneyReader.onActivityPreCreated(activity, null)

        assertEquals("e2e/mobile/flows/01-login-chat.yaml", requestThroughTheApp().headers[E2eJourney.HEADER])
    }

    @Test
    fun `a launch without the extra keeps the journey of the flow that runs`() {
        E2eJourney.readFrom(intentNaming("e2e/mobile/flows/05-deep-links.yaml"))
        // A deep link opened mid-flow: no extra, still that flow.
        E2eJourney.readFrom(intentNaming(null))
        E2eJourney.readFrom(intentNaming("  "))
        E2eJourney.readFrom(null)

        assertEquals("e2e/mobile/flows/05-deep-links.yaml", requestThroughTheApp().headers[E2eJourney.HEADER])
    }

    @Test
    fun `the next flow's launch replaces the journey`() {
        E2eJourney.readFrom(intentNaming("e2e/mobile/flows/01-login-chat.yaml"))
        E2eJourney.readFrom(intentNaming("e2e/mobile/flows/02-voice-overlay.yaml"))

        assertEquals("e2e/mobile/flows/02-voice-overlay.yaml", requestThroughTheApp().headers[E2eJourney.HEADER])
    }
}

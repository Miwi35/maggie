package com.maggie.app.data.auth

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import com.maggie.app.BuildConfig
import com.maggie.app.data.api.MaggieApiService
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The emulator's door, tested where it is compiled (MAG-98).
 *
 * This file is in `src/testE2e/`, so it only exists for the `e2e` flavor — the
 * same boundary as `E2eSignIn` itself. `.github/workflows/ci.yml` runs
 * `testE2eDebugUnitTest` in the `e2e-mobile-device` job; `mobile.yml` keeps running
 * the prod variant, where neither file exists.
 *
 * What matters here is the request, not the plumbing: the journeys below it all
 * start with a login, and a login that silently drops `X-E2E-Token` or signs in
 * as nobody fails later, as an empty dashboard.
 */
class E2eSignInTest {

    private val context: Context = mockk(relaxed = true)

    private fun signInWith(
        handler: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): Pair<E2eSignIn, MutableList<HttpRequestData>> {
        val seen = mutableListOf<HttpRequestData>()
        val client = HttpClient(
            MockEngine { request ->
                seen += request
                handler(request)
            },
        ) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        return E2eSignIn(MaggieApiService(client)) to seen
    }

    private fun loginResponse() = """
        {
          "token": "jwt-e2e",
          "refreshToken": "refresh-e2e",
          "mercureToken": "mercure-e2e",
          "user": {"id": "01JA", "email": "e2e@maggie.local", "name": "Camille Test", "avatar": null}
        }
    """.trimIndent()

    @Test
    fun `it posts the seeded email to the test login with the e2e token`() = runBlocking {
        val (signIn, seen) = signInWith {
            respond(
                loginResponse(),
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        val response = signIn.authenticate(context)

        assertEquals(1, seen.size)
        val request = seen.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("${BuildConfig.API_BASE_URL}/api/auth/e2e/login", request.url.toString())
        assertEquals(BuildConfig.E2E_LOGIN_TOKEN, request.headers["X-E2E-Token"])
        assertTrue(
            "the body must carry the seeded address, or the login 404s on an unknown user",
            request.bodyAsText().contains(BuildConfig.E2E_LOGIN_EMAIL),
        )

        assertEquals("jwt-e2e", response.token)
        assertEquals("refresh-e2e", response.refreshToken)
        assertEquals("mercure-e2e", response.mercureToken)
        assertEquals("e2e@maggie.local", response.user.email)
    }

    @Test
    fun `a launch argument signs in as another seeded user`() = runBlocking {
        val intent = mockk<Intent>()
        every { intent.getStringExtra(E2E_EMAIL_EXTRA) } returns "e2e-other@maggie.local"
        val activity = mockk<Activity>()
        every { activity.intent } returns intent
        // What `LocalContext.current` really is in a composable: a wrapper, not the activity.
        val wrapper = mockk<ContextWrapper>()
        every { wrapper.baseContext } returns activity

        val (signIn, seen) = signInWith {
            respond(
                loginResponse(),
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        signIn.authenticate(wrapper)

        assertTrue(seen.single().bodyAsText().contains("e2e-other@maggie.local"))
    }

    @Test
    fun `an activity launched without the argument keeps the flavor's default account`() = runBlocking {
        val intent = mockk<Intent>()
        every { intent.getStringExtra(E2E_EMAIL_EXTRA) } returns null
        val activity = mockk<Activity>()
        every { activity.intent } returns intent

        val (signIn, seen) = signInWith {
            respond(
                loginResponse(),
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        signIn.authenticate(activity)

        assertTrue(seen.single().bodyAsText().contains(BuildConfig.E2E_LOGIN_EMAIL))
    }

    @Test
    fun `a wrong token surfaces as a failure rather than a signed-in app`() = runBlocking {
        val (signIn, _) = signInWith { respondError(HttpStatusCode.Unauthorized) }

        val thrown = runCatching { signIn.authenticate(context) }.exceptionOrNull()

        assertNotNull("a 401 must not be swallowed", thrown)
    }

    @Test
    fun `an unseeded user surfaces as a failure, not as an empty dashboard`() = runBlocking {
        val (signIn, _) = signInWith { respondError(HttpStatusCode.NotFound) }

        val thrown = runCatching { signIn.authenticate(context) }.exceptionOrNull()

        assertNotNull("`task e2e:seed` not having run has to fail at the login", thrown)
    }

    /** The default the flavor bakes in; a journey logging in as nobody else is a broken journey. */
    @Test
    fun `the flavor points at the e2e stack and the seeded account`() {
        assertEquals("e2e@maggie.local", BuildConfig.E2E_LOGIN_EMAIL)
        assertTrue(BuildConfig.E2E_LOGIN_TOKEN.isNotEmpty())
        assertTrue(
            "the e2e flavor must never point at production",
            BuildConfig.API_BASE_URL.startsWith("http://"),
        )
    }
}

private fun HttpRequestData.bodyAsText(): String =
    (body as io.ktor.http.content.TextContent).text

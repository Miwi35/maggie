package com.maggie.app.data.auth

import android.content.Context
import com.maggie.app.BuildConfig
import com.maggie.app.data.api.MaggieApiService
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.Serializable

@Serializable
private data class E2eLoginRequest(val email: String)

/**
 * Signing a seeded user in without Google — the emulator's door (MAG-98).
 *
 * `POST /api/auth/e2e/login` (MAG-94) answers with the same payload as the
 * Google callback, so [AuthManager] stores exactly what it stores after a real
 * sign-in and no other screen takes an e2e-only branch. The server guards the
 * route three times over (registered only under `when@e2e`, refused outside
 * `e2e`, `X-E2E-Token` checked); this file is the fourth guard, because it is
 * compiled into the `e2e` flavor alone.
 *
 * The email is a build-time constant rather than something typed on screen: the
 * journey's subject is the login *screen*, and a text field holding a seeded
 * address would be an e2e-only widget — the one thing the seam exists to avoid.
 * It is `e2e@maggie.local` unless `-PE2E_LOGIN_EMAIL` says otherwise, and the
 * login fails with 404 rather than inventing a user when the seed has not run.
 */
class E2eSignIn(private val apiService: MaggieApiService) : SignInStrategy {
    override suspend fun authenticate(context: Context): AuthResponse =
        apiService.client.post("${BuildConfig.API_BASE_URL}/api/auth/e2e/login") {
            header("X-E2E-Token", BuildConfig.E2E_LOGIN_TOKEN)
            contentType(ContentType.Application.Json)
            setBody(E2eLoginRequest(BuildConfig.E2E_LOGIN_EMAIL))
        }.body()
}

/** What the `e2e` flavor signs in with. Its counterpart lives in `src/google/`. */
fun signInStrategy(apiService: MaggieApiService): SignInStrategy = E2eSignIn(apiService)

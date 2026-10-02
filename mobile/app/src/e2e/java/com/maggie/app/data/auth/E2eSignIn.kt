package com.maggie.app.data.auth

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
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
 * The email is never typed on screen: the journey's subject is the login
 * *screen*, and a text field holding a seeded address would be an e2e-only
 * widget — the one thing the seam exists to avoid. It is `e2e@maggie.local`
 * unless `-PE2E_LOGIN_EMAIL` says otherwise, or the flow launches the app with
 * an `e2e_email` launch argument (Maestro's `launchApp.arguments`, which arrive
 * as intent extras): the grocery journeys shop as the second seeded account,
 * whose list nothing else writes (MAG-178). The login fails with 404 rather
 * than inventing a user when the seed has not run.
 */
class E2eSignIn(private val apiService: MaggieApiService) : SignInStrategy {
    override suspend fun authenticate(context: Context): AuthResponse =
        apiService.client.post("${BuildConfig.API_BASE_URL}/api/auth/e2e/login") {
            header("X-E2E-Token", BuildConfig.E2E_LOGIN_TOKEN)
            contentType(ContentType.Application.Json)
            setBody(E2eLoginRequest(requestedEmail(context) ?: BuildConfig.E2E_LOGIN_EMAIL))
        }.body()
}

/** The launch argument a flow sets to sign in as another seeded user. */
const val E2E_EMAIL_EXTRA = "e2e_email"

/** The compose `LocalContext` is a wrapper around the activity, not the activity itself. */
internal fun requestedEmail(context: Context): String? {
    var current: Context? = context
    while (current != null) {
        if (current is Activity) {
            return current.intent?.getStringExtra(E2E_EMAIL_EXTRA)?.takeIf { it.isNotBlank() }
        }
        current = (current as? ContextWrapper)?.baseContext
    }
    return null
}

/** What the `e2e` flavor signs in with. Its counterpart lives in `src/google/`. */
fun signInStrategy(apiService: MaggieApiService): SignInStrategy = E2eSignIn(apiService)

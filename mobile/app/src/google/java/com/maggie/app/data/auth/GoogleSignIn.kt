package com.maggie.app.data.auth

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.maggie.app.BuildConfig
import com.maggie.app.data.api.MaggieApiService
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.Serializable

@Serializable
data class GoogleAuthRequest(val idToken: String)

/**
 * Signing in the way a real user does: Google Credential Manager, then the id
 * token exchanged for a JWT by the API.
 *
 * This file is in `src/google/`, a source set `dev` and `prod` share (see
 * `app/build.gradle.kts`). The `e2e` flavor does not compile it: it signs in
 * through `src/e2e/E2eSignIn.kt` instead, because the dialog below cannot be
 * driven by Maestro.
 */
class GoogleSignIn(private val apiService: MaggieApiService) : SignInStrategy {
    override suspend fun authenticate(context: Context): AuthResponse {
        val credentialManager = CredentialManager.create(context)

        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(BuildConfig.GOOGLE_CLIENT_ID)
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        val result = credentialManager.getCredential(context, request)
        val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(result.credential.data)

        return apiService.client.post("${BuildConfig.API_BASE_URL}/api/auth/google") {
            contentType(ContentType.Application.Json)
            setBody(GoogleAuthRequest(googleIdTokenCredential.idToken))
        }.body()
    }
}

/** What `dev` and `prod` sign in with. Its counterpart lives in `src/e2e/`. */
fun signInStrategy(apiService: MaggieApiService): SignInStrategy = GoogleSignIn(apiService)

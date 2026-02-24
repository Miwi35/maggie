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

@Serializable
data class AuthResponse(val token: String, val mercureToken: String? = null, val user: AuthUser)

@Serializable
data class AuthUser(val id: String, val email: String, val name: String, val avatar: String? = null)

class AuthManager(
    private val authRepository: AuthRepository,
    private val apiService: MaggieApiService,
) {
    suspend fun signInWithGoogle(context: Context): Result<AuthUser> = runCatching {
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
        val idToken = googleIdTokenCredential.idToken

        val response: AuthResponse = apiService.client.post("${BuildConfig.API_BASE_URL}/api/auth/google") {
            contentType(ContentType.Application.Json)
            setBody(GoogleAuthRequest(idToken))
        }.body()

        authRepository.saveAuth(
            token = response.token,
            mercureToken = response.mercureToken,
            id = response.user.id,
            name = response.user.name,
            email = response.user.email,
            avatar = response.user.avatar,
        )

        response.user
    }

    suspend fun logout() {
        authRepository.clear()
    }
}

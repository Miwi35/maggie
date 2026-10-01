package com.maggie.app.data.auth

import android.content.Context
import kotlinx.serialization.Serializable

@Serializable
data class AuthResponse(val token: String, val refreshToken: String? = null, val mercureToken: String? = null, val user: AuthUser)

@Serializable
data class AuthUser(val id: String, val email: String, val name: String, val avatar: String? = null)

/**
 * The door the app signs in through — one implementation per flavor.
 *
 * Google Credential Manager is a system dialog outside the app's view
 * hierarchy, so no emulator journey can tap it: Maestro sees the login screen
 * and then nothing (MAG-98). The `e2e` flavor therefore brings its own door, in
 * `src/e2e/`, and `dev` and `prod` share the Google one in `src/google/`.
 *
 * A seam and not a build flag, deliberately: the strategy linked into the APK
 * that ships is the Google one, and the test login is not compiled into it at
 * all. `src/main/` knows neither.
 */
fun interface SignInStrategy {
    /** The server's auth payload, however this flavor obtained it. */
    suspend fun authenticate(context: Context): AuthResponse
}

/**
 * Turns an authenticated payload into a signed-in app.
 *
 * Everything after the credential — which tokens are kept, under which keys —
 * lives here rather than in a [SignInStrategy], so the two doors cannot drift
 * apart in what they persist.
 */
class AuthManager(
    private val authRepository: AuthRepository,
    private val signInStrategy: SignInStrategy,
) {
    suspend fun signIn(context: Context): Result<AuthUser> = runCatching {
        val response = signInStrategy.authenticate(context)

        authRepository.saveAuth(
            token = response.token,
            refreshToken = response.refreshToken,
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

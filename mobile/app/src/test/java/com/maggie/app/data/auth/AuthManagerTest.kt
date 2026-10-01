package com.maggie.app.data.auth

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import io.mockk.mockk
import java.io.File
import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The half of signing in that is the same whichever door was used (MAG-98).
 *
 * `AuthManager` lost its Google code to `src/google/GoogleSignIn.kt` so that the
 * `e2e` flavor could bring its own door; what stayed is what every flavor owes —
 * persist the four values, return the user, and turn a refusal into a
 * `Result.failure` rather than an exception the login screen cannot show.
 */
class AuthManagerTest {

    private lateinit var scope: CoroutineScope
    private lateinit var file: File
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var repository: AuthRepository
    private val context: Context = mockk(relaxed = true)

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.IO)
        file = File.createTempFile("auth-manager", ".preferences_pb").also { it.delete() }
        dataStore = PreferenceDataStoreFactory.create(scope = scope) { file }
        repository = AuthRepository(dataStore, ReversingCipher())
    }

    @After
    fun tearDown() {
        scope.cancel()
        file.delete()
    }

    private fun managerOf(strategy: SignInStrategy) = AuthManager(repository, strategy)

    @Test
    fun `a successful sign-in persists every token the response carried`() = runBlocking {
        val user = AuthUser(id = "u1", email = "ada@example.com", name = "Ada", avatar = "https://example.test/a.png")
        val manager = managerOf { AuthResponse("jwt", "refresh", "mercure", user) }

        val result = manager.signIn(context)

        assertEquals(user, result.getOrNull())
        assertEquals("jwt", repository.getToken())
        assertEquals("refresh", repository.getRefreshToken())
        assertEquals("mercure", repository.getMercureToken())
        assertEquals("u1", repository.getUserId())
        assertTrue(repository.isAuthenticated.first())
    }

    @Test
    fun `a response without the optional tokens still signs the user in`() = runBlocking {
        val manager = managerOf {
            AuthResponse("jwt", null, null, AuthUser(id = "u1", email = "ada@example.com", name = "Ada"))
        }

        assertTrue(manager.signIn(context).isSuccess)
        assertEquals("jwt", repository.getToken())
        assertNull(repository.getRefreshToken())
        assertNull(repository.getMercureToken())
    }

    @Test
    fun `a refused sign-in is a failure and leaves the app signed out`() = runBlocking {
        val manager = managerOf { throw IllegalStateException("HTTP 401 Invalid e2e token") }

        val result = manager.signIn(context)

        assertTrue(result.isFailure)
        assertEquals("HTTP 401 Invalid e2e token", result.exceptionOrNull()?.message)
        assertNull(repository.getToken())
        assertFalse(repository.isAuthenticated.first())
    }

    @Test
    fun `logout clears what the sign-in stored`() = runBlocking {
        val manager = managerOf {
            AuthResponse("jwt", "refresh", "mercure", AuthUser(id = "u1", email = "ada@example.com", name = "Ada"))
        }
        manager.signIn(context)

        manager.logout()

        assertNull(repository.getToken())
        assertNull(repository.getUserId())
        assertFalse(repository.isAuthenticated.first())
    }

    /** Reversible and obviously not the real cipher, so a plaintext leak would be visible. */
    private class ReversingCipher : TokenCipher {
        override fun encrypt(plain: String): String =
            Base64.getEncoder().encodeToString(plain.reversed().toByteArray())

        override fun decrypt(stored: String): String? =
            String(Base64.getDecoder().decode(stored)).reversed()
    }
}

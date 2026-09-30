package com.maggie.app.data.auth

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import java.security.GeneralSecurityException
import java.security.ProviderException
import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AuthRepositoryTest {

    private val tokenKey = stringPreferencesKey("jwt_token")
    private val refreshKey = stringPreferencesKey("refresh_token")
    private val mercureKey = stringPreferencesKey("mercure_token")

    private lateinit var scope: CoroutineScope
    private lateinit var file: File
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var cipher: FakeCipher
    private lateinit var repository: AuthRepository

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.IO)
        file = File.createTempFile("auth", ".preferences_pb").also { it.delete() }
        dataStore = PreferenceDataStoreFactory.create(scope = scope) { file }
        cipher = FakeCipher()
        repository = AuthRepository(dataStore, cipher)
    }

    @After
    fun tearDown() {
        scope.cancel()
        file.delete()
    }

    private fun raw(): Preferences = runBlocking { dataStore.data.first() }

    private fun save() = runBlocking {
        repository.saveAuth("jwt", "refresh", "mercure", "u1", "Ada", "ada@example.com", null)
    }

    @Test
    fun `saveAuth stores tokens encrypted and reads them back in clear`() = runBlocking {
        save()

        listOf(
            Pair(tokenKey, "jwt"),
            Pair(refreshKey, "refresh"),
            Pair(mercureKey, "mercure"),
        ).forEach { (key, plain) ->
            val stored = raw()[key]!!
            assertNotEquals(plain, stored)
            assertFalse(stored.contains(plain))
        }
        assertEquals("jwt", repository.getToken())
        assertEquals("refresh", repository.getRefreshToken())
        assertEquals("mercure", repository.getMercureToken())
        assertEquals("jwt", repository.token.first())
        assertTrue(repository.isAuthenticated.first())
        assertEquals("u1", repository.getUserId())
    }

    @Test
    fun `updateTokens rotates encrypted tokens and keeps refresh token when null`() = runBlocking {
        save()

        repository.updateTokens("jwt2", null)

        assertEquals("jwt2", repository.getToken())
        assertEquals("refresh", repository.getRefreshToken())
        assertFalse(raw()[tokenKey]!!.contains("jwt2"))
    }

    @Test
    fun `legacy plaintext tokens are migrated without signing the user out`() = runBlocking {
        dataStore.edit {
            it[tokenKey] = "legacy-jwt"
            it[refreshKey] = "legacy-refresh"
            it[mercureKey] = "legacy-mercure"
        }

        assertEquals("legacy-jwt", repository.getToken())
        assertEquals("legacy-refresh", repository.getRefreshToken())
        assertEquals("legacy-mercure", repository.getMercureToken())
        listOf(tokenKey, refreshKey, mercureKey).forEach { key ->
            assertFalse(raw()[key]!!.contains("legacy"))
        }
    }

    @Test
    fun `token flow emits legacy token and migrates on first collection`() = runBlocking {
        dataStore.edit { it[tokenKey] = "legacy-jwt" }

        assertEquals("legacy-jwt", repository.token.first())
        assertFalse(raw()[tokenKey]!!.contains("legacy"))
    }

    @Test
    fun `undecryptable tokens read as signed out`() = runBlocking {
        save()
        cipher.keyLost = true

        assertNull(repository.getToken())
        assertNull(repository.getRefreshToken())
        assertNull(repository.token.first())
        assertFalse(repository.isAuthenticated.first())
    }

    @Test
    fun `migration is retried when the keystore is unavailable`() = runBlocking {
        dataStore.edit { it[tokenKey] = "legacy-jwt" }
        cipher.encryptError = GeneralSecurityException("keystore unavailable")

        assertEquals("legacy-jwt", repository.getToken())
        assertEquals("legacy-jwt", raw()[tokenKey])

        cipher.encryptError = null
        assertEquals("legacy-jwt", repository.getToken())
        assertFalse(raw()[tokenKey]!!.contains("legacy"))
    }

    @Test
    fun `a keystore runtime failure during migration does not crash the token flow`() = runBlocking {
        dataStore.edit { it[tokenKey] = "legacy-jwt" }
        cipher.encryptError = ProviderException("keystore daemon died")

        assertEquals("legacy-jwt", repository.token.first())
        assertEquals("legacy-jwt", raw()[tokenKey])
    }

    @Test
    fun `clear removes tokens and user`() = runBlocking {
        save()

        repository.clear()

        assertNull(repository.getToken())
        assertNull(repository.getUserId())
        assertFalse(repository.isAuthenticated.first())
    }

    private class FakeCipher : TokenCipher {
        var keyLost = false
        var encryptError: Exception? = null

        override fun encrypt(plain: String): String {
            encryptError?.let { throw it }
            return Base64.getEncoder().encodeToString(plain.reversed().toByteArray())
        }

        override fun decrypt(stored: String): String? {
            if (keyLost) return null
            return String(Base64.getDecoder().decode(stored)).reversed()
        }
    }
}

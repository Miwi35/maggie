package com.maggie.app.data.auth

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val Context.authDataStore: DataStore<Preferences> by preferencesDataStore(name = "auth")

class AuthRepository(
    private val dataStore: DataStore<Preferences>,
    private val cipher: TokenCipher,
) {
    constructor(context: Context) : this(context.authDataStore, KeystoreTokenCipher())

    private object Keys {
        val TOKEN = stringPreferencesKey("jwt_token")
        val REFRESH_TOKEN = stringPreferencesKey("refresh_token")
        val USER_ID = stringPreferencesKey("user_id")
        val USER_NAME = stringPreferencesKey("user_name")
        val USER_EMAIL = stringPreferencesKey("user_email")
        val USER_AVATAR = stringPreferencesKey("user_avatar")
        val MERCURE_TOKEN = stringPreferencesKey("mercure_token")
    }

    private val secretKeys = listOf(Keys.TOKEN, Keys.REFRESH_TOKEN, Keys.MERCURE_TOKEN)

    private val migrationLock = Mutex()
    @Volatile
    private var migrated = false

    val token: Flow<String?> = dataStore.data
        .onStart { migrateLegacyTokens() }
        .map { it.secret(Keys.TOKEN) }
        .flowOn(Dispatchers.IO)

    val isAuthenticated: Flow<Boolean> = token.map { it != null }

    suspend fun saveAuth(token: String, refreshToken: String?, mercureToken: String?, id: String, name: String, email: String, avatar: String?) {
        migrateLegacyTokens()
        dataStore.edit { prefs ->
            prefs[Keys.TOKEN] = seal(token)
            refreshToken?.let { prefs[Keys.REFRESH_TOKEN] = seal(it) }
            prefs[Keys.USER_ID] = id
            prefs[Keys.USER_NAME] = name
            prefs[Keys.USER_EMAIL] = email
            avatar?.let { prefs[Keys.USER_AVATAR] = it }
            mercureToken?.let { prefs[Keys.MERCURE_TOKEN] = seal(it) }
        }
    }

    /** Persist rotated tokens after a refresh, leaving user/profile data untouched. */
    suspend fun updateTokens(token: String, refreshToken: String?, mercureToken: String? = null) {
        migrateLegacyTokens()
        dataStore.edit { prefs ->
            prefs[Keys.TOKEN] = seal(token)
            refreshToken?.let { prefs[Keys.REFRESH_TOKEN] = seal(it) }
            mercureToken?.let { prefs[Keys.MERCURE_TOKEN] = seal(it) }
        }
    }

    suspend fun getToken(): String? = secret(Keys.TOKEN)

    suspend fun getRefreshToken(): String? = secret(Keys.REFRESH_TOKEN)

    suspend fun getMercureToken(): String? = secret(Keys.MERCURE_TOKEN)

    suspend fun getUserId(): String? = dataStore.data.first()[Keys.USER_ID]

    suspend fun clear() {
        dataStore.edit { it.clear() }
    }

    private suspend fun secret(key: Preferences.Key<String>): String? {
        migrateLegacyTokens()
        return dataStore.data.first().secret(key)
    }

    private fun Preferences.secret(key: Preferences.Key<String>): String? {
        val stored = this[key] ?: return null
        if (!stored.startsWith(ENCRYPTED_PREFIX)) return stored
        return cipher.decrypt(stored.removePrefix(ENCRYPTED_PREFIX))
    }

    private fun seal(plain: String): String = ENCRYPTED_PREFIX + cipher.encrypt(plain)

    /** Re-encrypts, once per process, tokens saved in clear by earlier app versions so nobody has to sign in again. */
    private suspend fun migrateLegacyTokens() {
        if (migrated) return
        migrationLock.withLock {
            if (migrated) return
            try {
                dataStore.edit { prefs ->
                    secretKeys.forEach { key ->
                        val stored = prefs[key]
                        if (stored != null && !stored.startsWith(ENCRYPTED_PREFIX)) {
                            prefs[key] = seal(stored)
                        }
                    }
                }
                migrated = true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Keystore unavailable: legacy values stay readable and the migration is retried on the next access.
            }
        }
    }

    private companion object {
        const val ENCRYPTED_PREFIX = "enc1:"
    }
}

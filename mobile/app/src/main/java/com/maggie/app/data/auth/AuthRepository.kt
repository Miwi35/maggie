package com.maggie.app.data.auth

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.authDataStore: DataStore<Preferences> by preferencesDataStore(name = "auth")

class AuthRepository(private val context: Context) {

    private object Keys {
        val TOKEN = stringPreferencesKey("jwt_token")
        val USER_ID = stringPreferencesKey("user_id")
        val USER_NAME = stringPreferencesKey("user_name")
        val USER_EMAIL = stringPreferencesKey("user_email")
        val USER_AVATAR = stringPreferencesKey("user_avatar")
    }

    val token: Flow<String?> = context.authDataStore.data.map { it[Keys.TOKEN] }

    val isAuthenticated: Flow<Boolean> = token.map { it != null }

    suspend fun saveAuth(token: String, id: String, name: String, email: String, avatar: String?) {
        context.authDataStore.edit { prefs ->
            prefs[Keys.TOKEN] = token
            prefs[Keys.USER_ID] = id
            prefs[Keys.USER_NAME] = name
            prefs[Keys.USER_EMAIL] = email
            avatar?.let { prefs[Keys.USER_AVATAR] = it }
        }
    }

    suspend fun getToken(): String? = context.authDataStore.data.first()[Keys.TOKEN]

    suspend fun clear() {
        context.authDataStore.edit { it.clear() }
    }
}

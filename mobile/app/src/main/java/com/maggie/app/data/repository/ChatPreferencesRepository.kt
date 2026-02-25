package com.maggie.app.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.chatPrefsDataStore: DataStore<Preferences> by preferencesDataStore(name = "chat_prefs")

class ChatPreferencesRepository(private val context: Context) {

    private object Keys {
        val LAST_READ_MESSAGE_ID = stringPreferencesKey("last_read_message_id")
    }

    suspend fun getLastReadMessageId(): String? =
        context.chatPrefsDataStore.data.first()[Keys.LAST_READ_MESSAGE_ID]

    suspend fun saveLastReadMessageId(id: String) {
        context.chatPrefsDataStore.edit { prefs ->
            prefs[Keys.LAST_READ_MESSAGE_ID] = id
        }
    }
}

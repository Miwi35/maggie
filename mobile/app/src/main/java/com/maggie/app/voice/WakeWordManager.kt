package com.maggie.app.voice

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private val Context.wakeWordDataStore by preferencesDataStore(name = "wake_word_prefs")

class WakeWordManager(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val enabledKey = booleanPreferencesKey("wake_word_enabled")

    val isEnabled: Flow<Boolean> = context.wakeWordDataStore.data
        .map { prefs -> prefs[enabledKey] == true }

    fun setEnabled(enabled: Boolean) {
        scope.launch {
            context.wakeWordDataStore.edit { prefs ->
                prefs[enabledKey] = enabled
            }
            if (enabled) {
                WakeWordService.start(context)
            } else {
                WakeWordService.stop(context)
            }
        }
    }

    fun restoreIfEnabled() {
        scope.launch {
            val enabled = isEnabled.first()
            if (enabled) {
                WakeWordService.start(context)
            }
        }
    }

    fun resumeListening() {
        scope.launch {
            val enabled = isEnabled.first()
            if (enabled) {
                WakeWordService.resumeListening(context)
            }
        }
    }
}

package com.maggie.app.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
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
                WakeWordNotifications.cancelReactivation(context)
            }
        }
    }

    /** Called with the app visible: the only moment Android 15 lets the microphone service start. */
    fun restoreIfEnabled() {
        scope.launch {
            if (!isEnabled.first()) return@launch
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                return@launch
            }
            try {
                WakeWordService.start(context)
                WakeWordNotifications.cancelReactivation(context)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Cannot restore the wake word service: ${e.message}")
                WakeWordNotifications.showReactivation(context)
            } catch (e: SecurityException) {
                Log.w(TAG, "Cannot restore the wake word service: ${e.message}")
                WakeWordNotifications.showReactivation(context)
            }
        }
    }

    /** A boot receiver cannot start the microphone service on Android 15: ask the user to tap instead. */
    fun onBootCompleted() {
        scope.launch {
            if (isEnabled.first()) {
                WakeWordNotifications.showReactivation(context)
            }
        }
    }

    fun resumeListening() {
        scope.launch {
            if (!isEnabled.first()) return@launch
            try {
                WakeWordService.resumeListening(context)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Cannot resume listening: ${e.message}")
                WakeWordNotifications.showReactivation(context)
            }
        }
    }

    fun isIgnoringBatteryOptimizations(): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    private companion object {
        const val TAG = "WakeWordManager"
    }
}

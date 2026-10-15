package com.maggie.app.data.interruption

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.maggie.app.R
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.interruptionDataStore: DataStore<Preferences> by preferencesDataStore(name = "interruption_prefs")

/** The chime and the vibration that announce Maggie, on unless the owner turned them off. */
class InterruptionPreferences(private val context: Context) {

    private val key = booleanPreferencesKey("sound")

    val soundEnabled: Flow<Boolean> = context.interruptionDataStore.data.map { it[key] ?: true }

    suspend fun isSoundEnabled(): Boolean = soundEnabled.first()

    suspend fun setSoundEnabled(enabled: Boolean) {
        context.interruptionDataStore.edit { it[key] = enabled }
    }
}

/** What announces an interruption besides the picture. */
fun interface InterruptionAlert {
    suspend fun alert()
}

/** Maggie's chime (the admin's two notes) and a short double buzz; silent when turned off or refused by the phone. */
class AndroidInterruptionAlert(
    private val context: Context,
    private val preferences: InterruptionPreferences,
) : InterruptionAlert {

    override suspend fun alert() {
        if (!preferences.isSoundEnabled()) return
        runCatching { vibrate() }
        runCatching { chime() }
    }

    private fun vibrate() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
        vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 40, 70, 40), -1))
    }

    // The very file the push channels ring, so foreground and background sound the same; released once played.
    private fun chime() {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val player = MediaPlayer.create(context, R.raw.maggie_chime, attributes, AudioManager.AUDIO_SESSION_ID_GENERATE) ?: return
        player.setOnCompletionListener { it.release() }
        player.start()
    }
}

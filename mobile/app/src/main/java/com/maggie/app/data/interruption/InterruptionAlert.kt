package com.maggie.app.data.interruption

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

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

/** Two soft notes and a short double buzz, like the admin's chime; silent when turned off or refused by the phone. */
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

    private fun chime() {
        val samples = NOTES_HZ.flatMapIndexed { index, hz -> note(hz, lead = index * NOTE_GAP_S) }
            .let { notes -> mix(notes) }
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(samples.size * 2)
            .build()
        track.write(samples, 0, samples.size)
        track.setNotificationMarkerPosition(samples.size)
        track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
            override fun onMarkerReached(t: AudioTrack) = t.release()
            override fun onPeriodicNotification(t: AudioTrack) = Unit
        })
        track.play()
    }

    // A sine with a soft attack and a long fade, silent for [lead] seconds first.
    private fun note(hz: Double, lead: Double): List<Pair<Int, Double>> {
        val start = (lead * SAMPLE_RATE).toInt()
        val length = (FADE_S * SAMPLE_RATE).toInt()
        return List(length) { i ->
            val t = i.toDouble() / SAMPLE_RATE
            val attack = (t / 0.02).coerceAtMost(1.0)
            (start + i) to (sin(2 * PI * hz * t) * attack * exp(-4.0 * t / FADE_S) * 0.35)
        }
    }

    private fun mix(notes: List<Pair<Int, Double>>): ShortArray {
        val out = DoubleArray(notes.maxOf { it.first } + 1)
        notes.forEach { (index, value) -> out[index] += value }
        return ShortArray(out.size) { (out[it].coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort() }
    }

    private companion object {
        val NOTES_HZ = listOf(880.0, 1318.5)
        const val NOTE_GAP_S = 0.14
        const val FADE_S = 0.9
        const val SAMPLE_RATE = 22_050
    }
}

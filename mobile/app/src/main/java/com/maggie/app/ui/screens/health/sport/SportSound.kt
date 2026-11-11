package com.maggie.app.ui.screens.health.sport

import android.media.AudioManager
import android.media.ToneGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The sound of the activity: one short beep at the end of a rest or of a held exercise — never Maggie's chime. */
interface SportSound {
    fun beep()
}

/** A [ToneGenerator] on the notifications' volume, released once the beep is over. */
class ToneSportSound(private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)) : SportSound {
    override fun beep() {
        val tone = runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, VOLUME) }.getOrNull() ?: return
        tone.startTone(ToneGenerator.TONE_PROP_BEEP, BEEP_MS)
        scope.launch {
            delay(BEEP_MS + RELEASE_MARGIN_MS)
            tone.release()
        }
    }

    private companion object {
        const val VOLUME = 60
        const val BEEP_MS = 200
        const val RELEASE_MARGIN_MS = 100L
    }
}

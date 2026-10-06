package com.maggie.app.voice

import android.content.Context
import android.os.Build

/** What dev and prod record with. Its counterpart lives in `src/e2e/`. */
@Suppress("UNUSED_PARAMETER")
fun audioRecorderFactory(context: Context): () -> AudioRecorder = { PcmAudioRecorder() }

/**
 * What dev and prod try first (MAG-222): Google's embedded engine, when the phone has
 * it. Below Android 13 there is no on-device recognizer to ask and no way to hand it
 * our own audio, so the voice path stays what it was — Whisper alone.
 *
 * Wrapped in [SegmentedDeviceSpeech] because one run of that engine is not one
 * sentence: it closes on a silence, button held, and the owner lost the half of his
 * phrase that came after a pause (retour de recette MAG-222). The wrapper gives it as
 * many runs as the hold needs and joins them.
 */
fun deviceSpeechFactory(context: Context): () -> DeviceSpeechRecognizer = {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        SegmentedDeviceSpeech { OnDeviceSpeechRecognizer(context) }
    } else {
        NoDeviceSpeech
    }
}

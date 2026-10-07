package com.maggie.app.voice

import android.content.Context
import java.io.File
import java.io.OutputStream

/**
 * The emulator has no sound card (`-noaudio`), so a real recording fails on press and
 * a hold could never reach the transcription. This one "records" a few placeholder
 * bytes, which nothing reads: the sentence comes from [ScriptedDeviceSpeech] (MAG-222).
 * Compiled into the `e2e` flavor alone.
 */
private class PlaceholderAudioRecorder : AudioRecorder {
    override fun start(file: File, pcmSink: OutputStream?) = file.writeBytes(byteArrayOf(0, 1, 2, 3))

    override fun stop() = Unit

    override fun release() = Unit
}

/** What the `e2e` flavor records with. Its counterpart lives in `src/google/`. */
@Suppress("UNUSED_PARAMETER")
fun audioRecorderFactory(context: Context): () -> AudioRecorder = { PlaceholderAudioRecorder() }

/**
 * A scripted engine under e2e (MAG-222): the CI emulator records silence and carries no
 * Google engine, so [ScriptedDeviceSpeech] hears one fixed sentence and the journey proves
 * what the overlay does with it. The real engine is verified on the owner's phone, in
 * recette.
 */
@Suppress("UNUSED_PARAMETER")
fun deviceSpeechFactory(context: Context): () -> DeviceSpeechRecognizer = { ScriptedDeviceSpeech() }

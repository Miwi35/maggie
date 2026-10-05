package com.maggie.app.voice

import android.content.Context
import java.io.File
import java.io.OutputStream

/**
 * The emulator has no sound card (`-noaudio`), so a real recording fails on press and
 * a hold could never reach the transcription. This one "records" a few placeholder
 * bytes: Whisper is simulated by WireMock in the e2e stack and answers one fixed
 * sentence whatever the clip holds (MAG-221). Compiled into the `e2e` flavor alone.
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
 * No embedded recognition under e2e, and that is the point (MAG-222). The CI emulator
 * carries no Google engine to drive, so the journey exercises the leg that *can* be
 * made deterministic: the Whisper fallback, and the absence of any model cleanup on the
 * way to Maggie. « Google first » is verified on the owner's phone, in recette.
 */
@Suppress("UNUSED_PARAMETER")
fun deviceSpeechFactory(context: Context): () -> DeviceSpeechRecognizer = { NoDeviceSpeech }

package com.maggie.app.voice

import android.content.Context
import java.io.File

/**
 * The emulator has no sound card (`-noaudio`), so a real recording fails on press and
 * a hold could never reach the transcription. This one "records" a few placeholder
 * bytes: Whisper is simulated by WireMock in the e2e stack and answers one fixed
 * sentence whatever the clip holds (MAG-221). Compiled into the `e2e` flavor alone.
 */
private class PlaceholderAudioRecorder : AudioRecorder {
    override fun start(file: File) = file.writeBytes(byteArrayOf(0, 1, 2, 3))

    override fun stop() = Unit

    override fun release() = Unit
}

/** What the `e2e` flavor records with. Its counterpart lives in `src/google/`. */
@Suppress("UNUSED_PARAMETER")
fun audioRecorderFactory(context: Context): () -> AudioRecorder = { PlaceholderAudioRecorder() }

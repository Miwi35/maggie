package com.maggie.app.voice

import android.content.Context

/** What dev and prod record with. Its counterpart lives in `src/e2e/`. */
fun audioRecorderFactory(context: Context): () -> AudioRecorder = { MediaAudioRecorder(context) }

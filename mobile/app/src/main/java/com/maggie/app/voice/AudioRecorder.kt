package com.maggie.app.voice

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

interface AudioRecorder {
    fun start(file: File)

    fun stop()

    fun release()
}

class MediaAudioRecorder(private val context: Context) : AudioRecorder {
    private var recorder: MediaRecorder? = null

    @Suppress("DEPRECATION")
    override fun start(file: File) {
        val created = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            MediaRecorder()
        }
        recorder = created
        created.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(128_000)
            setAudioSamplingRate(44_100)
            setOutputFile(file.absolutePath)
            prepare()
            start()
        }
    }

    override fun stop() {
        recorder?.stop()
    }

    override fun release() {
        recorder?.release()
        recorder = null
    }
}

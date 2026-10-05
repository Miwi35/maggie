package com.maggie.app.voice

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

interface AudioRecorder {
    /**
     * Record into [file] until [stop].
     *
     * When [pcmSink] is given, every buffer read is also written there as raw PCM: the
     * app owns the microphone once and feeds the phone's recognition engine through
     * that stream, so the same words are available to Whisper afterwards without the
     * owner repeating them (MAG-222). The sink belongs to whoever opened it — the
     * recorder writes to it and never closes it.
     */
    fun start(file: File, pcmSink: OutputStream? = null)

    fun stop()

    fun release()
}

/**
 * Microphone capture as 16-bit PCM, written out as a WAV file.
 *
 * Raw rather than AAC because that is the only thing the recognition engine can be
 * handed (`RecognizerIntent.EXTRA_AUDIO_SOURCE` takes PCM), and one capture split two
 * ways beats two apps fighting over the microphone. Whisper takes WAV just as happily;
 * 16 kHz mono is what speech recognition wants and keeps a minute of audio under
 * 2 MB.
 */
class PcmAudioRecorder : AudioRecorder {
    companion object {
        const val SAMPLE_RATE = 16_000
        const val CHANNEL_COUNT = 1
        const val BITS_PER_SAMPLE = 16
        const val FILE_EXTENSION = "wav"

        private const val TAG = "PcmAudioRecorder"
        private const val HEADER_BYTES = 44
    }

    private var record: AudioRecord? = null
    private var pump: Thread? = null

    @Volatile
    private var recording = false

    override fun start(file: File, pcmSink: OutputStream?) {
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        // Twice the minimum, so a scheduling hiccup does not drop audio. The fallback
        // is half a second's worth, for a device that refuses to size it.
        val bufferSize = if (minBuffer > 0) minBuffer * 2 else SAMPLE_RATE

        // VOICE_RECOGNITION rather than MIC: no automatic gain, no call-tuned noise
        // suppression — what both engines are trained on.
        val opened = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
        )
        if (opened.state != AudioRecord.STATE_INITIALIZED) {
            opened.release()
            throw IllegalStateException("AudioRecord could not open the microphone")
        }

        record = opened
        recording = true
        opened.startRecording()
        pump = Thread({ drain(opened, file, pcmSink, bufferSize) }, "voice-pcm").apply { start() }
    }

    override fun stop() {
        recording = false
        pump?.join(2_000)
        pump = null
        try {
            record?.stop()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "AudioRecord was not recording", e)
        }
    }

    override fun release() {
        recording = false
        pump?.join(500)
        pump = null
        record?.release()
        record = null
    }

    /** Reads the microphone until [stop], into the file and — while it accepts it — the sink. */
    private fun drain(from: AudioRecord, file: File, pcmSink: OutputStream?, bufferSize: Int) {
        var sink = pcmSink
        var samples = 0L
        try {
            RandomAccessFile(file, "rw").use { out ->
                out.setLength(0)
                out.write(ByteArray(HEADER_BYTES)) // placeholder: the sizes are only known at the end
                val buffer = ByteArray(bufferSize)
                while (recording) {
                    val read = from.read(buffer, 0, buffer.size)
                    if (read < 0) {
                        Log.w(TAG, "AudioRecord.read returned $read, stopping")
                        break
                    }
                    if (read == 0) continue
                    out.write(buffer, 0, read)
                    samples += read
                    try {
                        sink?.write(buffer, 0, read)
                    } catch (e: IOException) {
                        // The engine closed its end — it is done, or it never wanted
                        // our audio. Keep recording: Whisper still needs the file.
                        Log.i(TAG, "The recognition engine stopped reading", e)
                        sink = null
                    }
                }
                out.seek(0)
                out.write(wavHeader(samples))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Recording failed", e)
        }
    }

    /** The 44-byte RIFF header for [dataBytes] of mono 16-bit PCM. */
    private fun wavHeader(dataBytes: Long): ByteArray {
        val byteRate = SAMPLE_RATE * CHANNEL_COUNT * BITS_PER_SAMPLE / 8
        return ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt((36 + dataBytes).toInt())
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(16) // size of this chunk
            putShort(1) // PCM, uncompressed
            putShort(CHANNEL_COUNT.toShort())
            putInt(SAMPLE_RATE)
            putInt(byteRate)
            putShort((CHANNEL_COUNT * BITS_PER_SAMPLE / 8).toShort()) // block align
            putShort(BITS_PER_SAMPLE.toShort())
            put("data".toByteArray())
            putInt(dataBytes.toInt())
        }.array()
    }
}

package com.maggie.app.voice

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile

interface AudioRecorder {
    /**
     * Record into [file] until [stop].
     *
     * When [pcmSink] is given, every buffer read is also handed to the phone's
     * recognition engine through it: the app owns the microphone once and feeds both
     * readers, so the same words are available to Whisper afterwards without the owner
     * repeating them (MAG-222). The recorder takes the sink over and closes it when it
     * stops — that close is the engine's end-of-sentence signal.
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
 * 16 kHz mono is what speech recognition wants and keeps a minute of audio under 2 MB.
 */
class PcmAudioRecorder : AudioRecorder {
    companion object {
        const val SAMPLE_RATE = 16_000
        const val CHANNEL_COUNT = 1
        const val BITS_PER_SAMPLE = 16
        const val FILE_EXTENSION = "wav"

        private const val TAG = "PcmAudioRecorder"
        private const val PUMP_TIMEOUT_MS = 2_000L
    }

    private var record: AudioRecord? = null
    private var pump: Thread? = null
    private var tee: PcmTee? = null

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
        tee = pcmSink?.let { PcmTee(it) }
        recording = true
        opened.startRecording()
        pump = Thread({ drain(opened, file, bufferSize) }, "voice-pcm").apply { start() }
    }

    override fun stop() {
        stopPump()
        try {
            record?.stop()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "AudioRecord was not recording", e)
        }
    }

    override fun release() {
        stopPump()
        record?.release()
        record = null
    }

    /**
     * End the capture and wait for the file to be finalised — the clip Whisper may be
     * about to read — then let the engine go. Nothing here can block for long: the
     * pump only ever waits on `AudioRecord.read`, one buffer deep, and the engine's
     * pipe is fed by [PcmTee] on its own thread.
     */
    private fun stopPump() {
        recording = false
        pump?.let { thread ->
            thread.join(PUMP_TIMEOUT_MS)
            if (thread.isAlive) Log.e(TAG, "The recording thread did not stop; the clip may be truncated")
        }
        pump = null
        tee?.let {
            it.finish()
            // A gap in what the engine heard is the one way this design can put wrong
            // words in front of Maggie — a short, confident transcript the judge
            // accepts. Worth a line in logcat when a bug report comes back.
            if (it.dropped > 0) Log.w(TAG, "The engine was too slow for ${it.dropped} buffers")
        }
        tee = null
    }

    /** Reads the microphone until [stop], into the file and — best effort — the engine. */
    private fun drain(from: AudioRecord, file: File, bufferSize: Int) {
        var dataBytes = 0L
        try {
            RandomAccessFile(file, "rw").use { out ->
                try {
                    out.setLength(0)
                    out.write(ByteArray(WavHeader.BYTES)) // placeholder: the sizes are known at the end
                    val buffer = ByteArray(bufferSize)
                    while (recording) {
                        val read = from.read(buffer, 0, buffer.size)
                        if (read < 0) {
                            Log.w(TAG, "AudioRecord.read returned $read, stopping")
                            break
                        }
                        if (read == 0) continue
                        out.write(buffer, 0, read)
                        dataBytes += read
                        tee?.offer(buffer, read)
                    }
                } finally {
                    // In a finally so that a clip cut short by an error is still a
                    // playable WAV rather than 44 zero bytes and silence. Caught here
                    // rather than thrown: a failure writing the header would replace
                    // whatever really went wrong, which is what the log is for.
                    try {
                        out.seek(0)
                        out.write(WavHeader.forPcm(dataBytes, SAMPLE_RATE, CHANNEL_COUNT, BITS_PER_SAMPLE))
                    } catch (e: Exception) {
                        Log.e(TAG, "Could not finalise the WAV header", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Recording failed", e)
        }
    }
}

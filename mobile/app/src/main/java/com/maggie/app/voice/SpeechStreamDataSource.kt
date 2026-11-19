package com.maggie.app.voice

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec

/** Feeds the player from a [SpeechStream] still being received. The stream belongs to its owner: closing leaves it open. */
class SpeechStreamDataSource(private val stream: SpeechStream) : BaseDataSource(false) {
    private var uri: Uri? = null
    private var position = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        position = dataSpec.position
        opened = true
        transferStarted(dataSpec)
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val count = stream.readAt(position, buffer, offset, length)
        if (count < 0) return C.RESULT_END_OF_INPUT
        position += count
        bytesTransferred(count)
        return count
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    class Factory(private val stream: SpeechStream) : DataSource.Factory {
        override fun createDataSource(): DataSource = SpeechStreamDataSource(stream)
    }
}

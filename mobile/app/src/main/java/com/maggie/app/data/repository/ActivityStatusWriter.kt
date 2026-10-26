package com.maggie.app.data.repository

import com.maggie.app.data.model.ActivityStatusRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** What the writer sends: the status as it now is, or its end. */
sealed interface ActivityWrite {
    data class Put(val request: ActivityStatusRequest) : ActivityWrite

    data object Delete : ActivityWrite
}

/**
 * The writes of one activity's status (MAG-615), one at a time and in order: never two requests in flight.
 * While one is on its way, only the last change that arrives is kept and goes next; a [delete] drops a put still
 * waiting and goes after the one in flight. A failed request is dropped: the next change writes the whole state again.
 */
class ActivityStatusWriter(
    /** Outlives the screen: the last change still goes out after the activity is left. */
    private val scope: CoroutineScope,
    private val send: suspend (ActivityWrite) -> Unit,
) {
    private val lock = Any()
    private var waiting: ActivityWrite? = null
    private var draining = false
    private val busy = MutableStateFlow(false)

    fun put(request: ActivityStatusRequest) = submit(ActivityWrite.Put(request))

    fun delete() = submit(ActivityWrite.Delete)

    /** Returns once every write handed over so far was sent (or failed): a read after it sees them. */
    suspend fun idle() {
        busy.first { !it }
    }

    private fun submit(write: ActivityWrite) {
        val start = synchronized(lock) {
            waiting = write
            (!draining).also { draining = true; busy.value = true }
        }
        if (start) scope.launch { drain() }
    }

    private suspend fun drain() {
        while (true) {
            val next = synchronized(lock) {
                waiting.also {
                    waiting = null
                    if (it == null) {
                        draining = false
                        busy.value = false
                    }
                }
            } ?: return
            try {
                send(next)
            } catch (e: CancellationException) {
                synchronized(lock) { draining = false; busy.value = false }
                throw e
            } catch (_: Exception) {
                // Dropped: the next change writes the state again.
            }
        }
    }
}

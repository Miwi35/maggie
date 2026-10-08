package com.maggie.app.data.interruption

import com.maggie.app.data.fcm.PushNotifier
import com.maggie.app.data.fcm.PushPayload
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What Maggie says while the app is open, one at a time (MAG-314).
 *
 * Every source — the push that reaches the app in the foreground, the Mercure feeds of
 * notifications and held actions, a postponed one coming back — [offer]s here. A question
 * (a held action) goes ahead of the rest, the others keep their order, and none is shown
 * twice: the same message arrives by FCM *and* by Mercure, and its key is what tells them apart.
 *
 * The queue only decides *what* is shown. How long it stays (30 s) belongs to the screen
 * that shows it, so a message raised while the app is in the background is not used up unseen.
 */
class InterruptionCenter(private val now: () -> Long = System::currentTimeMillis) {

    private val lock = Any()
    private val queue = ArrayDeque<PushPayload>()
    private val seen = LinkedHashSet<String>()
    private val postponed = HashMap<String, Long>()
    private val rung = HashSet<String>()
    private val _current = MutableStateFlow<PushPayload?>(null)

    /** The one on screen, null when Maggie has nothing to say. */
    val current: StateFlow<PushPayload?> = _current

    /**
     * False when it was already offered (shown, queued, answered or timed out), is left to its own source,
     * or was put off with « Plus tard » and is not due yet. [reshow] is the postponement's own alarm
     * coming back: it is due by definition.
     */
    fun offer(payload: PushPayload, reshow: Boolean = false): Boolean = synchronized(lock) {
        if (!payload.interrupts) return false
        if (reshow) {
            postponed.remove(payload.key)
        } else if (postponed[payload.key]?.let { it > now() } == true) {
            return false
        }
        if (!seen.add(payload.key)) return false
        if (seen.size > MAX_REMEMBERED) seen.remove(seen.first())

        if (_current.value == null) {
            _current.value = payload
        } else if (payload.isQuestion) {
            val firstOther = queue.indexOfFirst { !it.isQuestion }
            if (firstOther < 0) queue.addLast(payload) else queue.add(firstOther, payload)
        } else {
            queue.addLast(payload)
        }
        true
    }

    /** The owner asked for it again (a tap on the notification): on screen now, the one it replaces waits its turn. */
    fun reopen(payload: PushPayload) = synchronized(lock) {
        seen.add(payload.key)
        val showing = _current.value
        if (showing?.key == payload.key) return
        queue.removeAll { it.key == payload.key }
        if (showing != null) queue.addFirst(showing)
        _current.value = payload
    }

    /** Answered, timed out, read elsewhere or deleted: it is gone, and the next one comes. It is not offered again. */
    fun close(key: String) = synchronized(lock) {
        // Remembered even if it was never shown: read before its push arrived, it must not come in late.
        seen.add(key)
        if (_current.value?.key == key) {
            _current.value = queue.removeFirstOrNull()
        } else {
            queue.removeAll { it.key == key }
        }
    }

    /**
     * « Plus tard »: gone now, and not offered again — by the feeds either, which re-offer what is
     * pending each time the app opens — until [delayMs] has passed or its reminder comes back.
     */
    fun postpone(key: String, delayMs: Long = PushNotifier.POSTPONE_MS) = synchronized(lock) {
        close(key)
        seen.remove(key)
        postponed[key] = now() + delayMs
    }

    /** True once per interruption, whichever screen shows it: switching to the overlay must not ring again. */
    fun announce(key: String): Boolean = synchronized(lock) { rung.add(key) }

    /** Signed out: what was said to one account is not said to the next. */
    fun clear() = synchronized(lock) {
        queue.clear()
        seen.clear()
        postponed.clear()
        rung.clear()
        _current.value = null
    }

    private companion object {
        const val MAX_REMEMBERED = 500
    }
}

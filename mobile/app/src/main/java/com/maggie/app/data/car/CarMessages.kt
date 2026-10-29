package com.maggie.app.data.car

import com.maggie.app.data.fcm.PushPayload
import com.maggie.app.data.interruption.key
import java.util.concurrent.ConcurrentHashMap

/**
 * Maggie's messages while the phone is in the car (MAG-683): all of them leave as the conversation notification
 * Android Auto reads on request (MAG-682), the app open or not, and the phone shows neither toaster nor overlay.
 *
 * A message that waits for an answer (an interruption, a reminder, a validation) is said once more after
 * [NUDGE_MS] if nobody reacted — once, never twice. When the car goes, the ones still waiting come back as the
 * usual interruption on the phone.
 *
 * Nothing here knows Android: the notification, the alarm and the car are lent by the caller.
 */
class CarMessages(
    private val isConnected: () -> Boolean,
    /** Posts [payload] as a conversation; [nudge] when it is the one reminder of a message left unanswered. */
    private val post: (payload: PushPayload, nudge: Boolean) -> Unit,
    /** Whether the notification filed under [tag] is still in the tray: nobody has answered, read or opened it. */
    private val isShowing: (tag: String) -> Boolean,
    private val scheduleNudge: (payload: PushPayload, delayMs: Long) -> Unit,
    private val cancelNudge: (payload: PushPayload) -> Unit,
) {

    private val waiting = ConcurrentHashMap<String, PushPayload>()
    private val nudged = ConcurrentHashMap.newKeySet<String>()

    val inCar: Boolean get() = isConnected()

    /** True when the message was said in the car; false when the phone is not in one, and nothing was done. */
    fun say(payload: PushPayload): Boolean {
        if (!isConnected()) return false
        // The same question arrives by FCM and by Mercure: said once, not alerted twice, nudged from its first arrival.
        if (payload.awaitsAnswer && waiting.containsKey(payload.key) && isShowing(payload.systemTag)) return true
        post(payload, false)
        if (payload.awaitsAnswer && waiting.put(payload.key, payload) == null) scheduleNudge(payload, NUDGE_MS)
        return true
    }

    /**
     * The alarm of [say]: the message is told again if it is still in the tray, and never scheduled again.
     * The tray is the only witness: the alarm outlives the process, which no longer remembers what it waited for,
     * and the car going away takes the notification down before this fires.
     */
    fun nudge(payload: PushPayload) {
        if (!isShowing(payload.systemTag)) return
        if (nudged.add(payload.key)) post(payload, true)
    }

    /** Read, answered or taken down elsewhere: there is nothing left to wait on. [tag]: the notification's. */
    fun forget(tag: String) {
        val payload = waiting.values.firstOrNull { it.systemTag == tag } ?: return
        waiting.remove(payload.key)
        nudged.remove(payload.key)
        cancelNudge(payload)
    }

    /**
     * The car is gone. What still sits unanswered in the tray is returned for the phone to say as it usually does;
     * the rest was answered, and needs nothing.
     */
    fun left(): List<PushPayload> {
        val back = waiting.values.filter { isShowing(it.systemTag) }
        waiting.values.forEach(cancelNudge)
        waiting.clear()
        nudged.clear()
        return back
    }

    companion object {
        const val NUDGE_MS = 5 * 60 * 1000L
    }
}

package com.maggie.app.data.fcm

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.maggie.app.data.interruption.InterruptionCenter

/**
 * Where a push goes once it reaches the app (MAG-314): with the app open, Maggie interrupts
 * inside it and the system shows nothing; otherwise it is the system notification. Never both,
 * so a message is never said twice.
 */
class PushDelivery(
    private val center: InterruptionCenter,
    private val notifier: () -> PushNotifier,
    private val isForeground: () -> Boolean = ::appIsForeground,
) {

    fun deliver(payload: PushPayload) {
        if (isForeground()) center.offer(payload) else notifier().show(payload)
    }

    private companion object {
        fun appIsForeground(): Boolean =
            ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    }
}

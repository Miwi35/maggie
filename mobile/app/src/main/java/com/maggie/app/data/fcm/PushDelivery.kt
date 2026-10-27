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

    /** [reshow]: the alarm of a « Plus tard » coming back, so the postponement is over. */
    fun deliver(payload: PushPayload, reshow: Boolean = false) {
        // « Pars maintenant » (MAG-658) is the system's strong interruption, open or not: its full screen and
        // its « Y aller » (a link to Waze or Maps) are not something the in-app toaster can say.
        if (payload.isDeparture) {
            notifier().show(payload)
            return
        }
        if (isForeground()) center.offer(payload, reshow) else notifier().show(payload)
    }

    /**
     * A notification seen on another device (MAG-582, the API's `withdraw` data message): it leaves
     * the tray, and the interruption if the app shows it. Android hands this message to the app
     * even in the background, which is the only way to reach a phone in a pocket.
     */
    fun withdraw(tag: String, notificationId: String?) {
        notifier().close(tag)
        notificationId?.let(center::close)
    }

    private companion object {
        fun appIsForeground(): Boolean =
            ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    }
}

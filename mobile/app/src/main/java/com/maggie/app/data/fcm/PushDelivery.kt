package com.maggie.app.data.fcm

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.maggie.app.data.car.CarMessages
import com.maggie.app.data.interruption.InterruptionCenter
import com.maggie.app.data.interruption.interrupts
import java.time.Instant

/**
 * Where a push goes once it reaches the app (MAG-314): with the app open, Maggie interrupts
 * inside it and the system shows nothing; otherwise it is the system notification. Never both,
 * so a message is never said twice. In the car (MAG-683) every message is the conversation notification the car reads,
 * the app open or not.
 */
class PushDelivery(
    private val center: InterruptionCenter,
    private val notifier: () -> PushNotifier,
    private val car: CarMessages,
    private val isForeground: () -> Boolean = ::appIsForeground,
    private val now: () -> Instant = Instant::now,
) {

    /** [reshow]: the alarm of a « Plus tard » coming back, so the postponement is over. */
    fun deliver(payload: PushPayload, reshow: Boolean = false) {
        // A push held back for hours is not worth saying once what it announced is over (MAG-679).
        if (payload.isExpired(now())) return
        // « Pars maintenant » (MAG-658) is the system's strong interruption, open or not: its full screen and
        // its « Y aller » (a link to Waze or Maps) are not something the in-app toaster can say.
        if (payload.isDeparture) {
            if (!car.say(payload)) notifier().show(payload)
            return
        }
        // Open, the center is where the car is told too: the Mercure feeds reach it without passing here.
        if (isForeground()) {
            center.offer(payload, reshow)
        } else if (!car.say(payload)) {
            notifier().show(payload)
        }
    }

    /** The car is gone: what was left unanswered is said on the phone as usual, in the app or in the tray. */
    fun backOnPhone(payloads: List<PushPayload>) {
        payloads.forEach { payload ->
            notifier().close(payload.systemTag)
            if (!isForeground() || !payload.interrupts) {
                notifier().show(payload)
            } else if (payload.interrupts) {
                center.reopen(payload)
            }
        }
    }

    /**
     * A notification seen on another device (MAG-582, the API's `withdraw` data message): it leaves
     * the tray, and the interruption if the app shows it. Android hands this message to the app
     * even in the background, which is the only way to reach a phone in a pocket.
     */
    fun withdraw(tag: String, notificationId: String?) {
        notifier().close(tag)
        car.forget(tag)
        notificationId?.let(center::close)
    }

    private companion object {
        fun appIsForeground(): Boolean =
            ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    }
}

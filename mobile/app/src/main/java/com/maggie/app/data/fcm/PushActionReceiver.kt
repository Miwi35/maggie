package com.maggie.app.data.fcm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.maggie.app.data.car.CarMessages
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** The buttons of a push notification, and the alarm that brings a postponed one back. */
class PushActionReceiver : BroadcastReceiver(), KoinComponent {

    private val handler: PushActionHandler by inject()
    private val delivery: PushDelivery by inject()
    private val car: CarMessages by inject()

    override fun onReceive(context: Context, intent: Intent) {
        val payload = PushIntents.payloadOf(intent) ?: return
        val notifier = PushNotifier(context)

        if (intent.action == ACTION_RESHOW) {
            delivery.deliver(payload, reshow = true)
            return
        }

        if (intent.action == ACTION_NUDGE) {
            car.nudge(payload)
            return
        }

        val kind = PushActionKind.entries.firstOrNull { actionFor(it) == intent.action } ?: return
        val reply = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_REPLY)?.toString()

        // The answer is a network call: the receiver stays alive until it is done.
        val pending = goAsync()
        scope.launch {
            try {
                when (val outcome = handler.handle(kind, payload, reply) { late -> notifier.show(payload, note = late.message) }) {
                    PushOutcome.Closed -> {
                        notifier.close(payload.systemTag)
                        car.forget(payload.systemTag)
                    }
                    PushOutcome.Postponed -> notifier.postpone(payload)
                    // The car presses « Marquer comme lu » by itself: a failure must not turn the message into an error note.
                    is PushOutcome.Retry -> if (kind != PushActionKind.MARK_AS_READ) notifier.show(payload, note = outcome.message)
                    is PushOutcome.Settled -> notifier.show(payload, note = outcome.message, withActions = false)
                    // The same notification, now the bravo: its words in place of the reminder's, nothing to press.
                    is PushOutcome.Rewarded -> notifier.show(payload.copy(title = outcome.message, text = null), withActions = false)
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val KEY_REPLY = "reply"
        const val ACTION_RESHOW = "com.maggie.app.push.RESHOW"
        const val ACTION_NUDGE = "com.maggie.app.push.NUDGE"

        fun actionFor(kind: PushActionKind): String = "com.maggie.app.push.${kind.name}"

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

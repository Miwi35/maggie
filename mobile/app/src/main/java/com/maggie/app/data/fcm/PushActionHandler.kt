package com.maggie.app.data.fcm

import android.util.Log
import com.maggie.app.data.api.ApprovalDecisionException
import com.maggie.app.data.api.MaggieApiService
import kotlinx.coroutines.CancellationException

/** What the notification becomes once a button has been pressed. */
sealed interface PushOutcome {
    /** Answered: the notification goes away. */
    data object Closed : PushOutcome

    /** « Plus tard »: it goes away and comes back later. */
    data object Postponed : PushOutcome

    /** The answer did not get through: the notification stays, with the reason, so the button can be pressed again. */
    data class Retry(val message: String) : PushOutcome

    /** The request is no longer open (already answered elsewhere, or expired): there is nothing left to press. */
    data class Settled(val message: String) : PushOutcome
}

/**
 * Carries out the button of a notification, with the same effect as in the app: an answer
 * given here closes the request everywhere, since marking the notification read is what
 * the app and the admin listen to.
 */
class PushActionHandler(private val apiService: MaggieApiService) {

    suspend fun handle(kind: PushActionKind, payload: PushPayload, reply: String? = null): PushOutcome = when (kind) {
        // Opened by the system, never a broadcast: a notification action cannot start an activity from here.
        PushActionKind.GO -> PushOutcome.Closed
        PushActionKind.LATER -> PushOutcome.Postponed
        PushActionKind.OK, PushActionKind.DONE -> markRead(payload)
        PushActionKind.REPLY -> reply(reply)
        PushActionKind.APPROVE -> decide(payload) { apiService.approve(it) }
        PushActionKind.DENY -> decide(payload) { apiService.deny(it) }
    }

    private suspend fun markRead(payload: PushPayload): PushOutcome = attempt {
        apiService.markNotificationRead(payload.notificationId)
        PushOutcome.Closed
    }

    private suspend fun reply(text: String?): PushOutcome {
        val message = text?.trim().orEmpty()
        if (message.isEmpty()) return PushOutcome.Retry("Écrivez votre réponse, puis envoyez-la.")
        return attempt {
            apiService.sendChat(message)
            PushOutcome.Closed
        }
    }

    private suspend fun decide(payload: PushPayload, decision: suspend (String) -> Unit): PushOutcome {
        val approvalId = payload.approvalId ?: return PushOutcome.Settled("Cette demande n'est plus en attente.")
        val outcome = try {
            decision(approvalId)
            PushOutcome.Closed
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApprovalDecisionException) {
            if (e.isFinal) PushOutcome.Settled("Cette demande n'est plus en attente.") else PushOutcome.Retry(UNREACHABLE)
        } catch (e: Exception) {
            Log.w(TAG, "Approval decision failed: ${e.message}")
            PushOutcome.Retry(UNREACHABLE)
        }
        // The decision is made; whether the notification is marked read must not undo it.
        if (outcome !is PushOutcome.Retry) {
            runCatching { apiService.markNotificationRead(payload.notificationId) }
        }
        return outcome
    }

    private suspend fun attempt(block: suspend () -> PushOutcome): PushOutcome = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Notification action failed: ${e.message}")
        PushOutcome.Retry(UNREACHABLE)
    }

    private companion object {
        const val TAG = "PushActionHandler"
        const val UNREACHABLE = "Maggie est injoignable. Réessayez."
    }
}

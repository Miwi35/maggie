package com.maggie.app.data.fcm

import android.util.Log
import com.maggie.app.data.api.ApiRefusalException
import com.maggie.app.data.api.ApprovalDecisionException
import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.model.TaskReminderAnswer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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

    /** « Fait » on a task reminder (MAG-510): the notification says the bravo in place of the reminder, with no button. */
    data class Rewarded(val message: String) : PushOutcome
}

/**
 * Carries out the button of a notification, with the same effect as in the app: an answer
 * given here closes the request everywhere, since marking the notification read is what
 * the app and the admin listen to.
 */
class PushActionHandler(
    private val apiService: MaggieApiService,
    private val sendScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val replyBudgetMs: Long = REPLY_BUDGET_MS,
) {

    /**
     * [onLateFailure] is called when a reply, still being processed by Maggie once the
     * receiver's time ran out, ends up failing: the notification must then say so.
     */
    suspend fun handle(
        kind: PushActionKind,
        payload: PushPayload,
        reply: String? = null,
        onLateFailure: (PushOutcome.Retry) -> Unit = {},
    ): PushOutcome = when (kind) {
        // Opened by the system, never a broadcast: a notification action cannot start an activity from here.
        PushActionKind.GO -> PushOutcome.Closed
        PushActionKind.LATER -> PushOutcome.Postponed
        PushActionKind.OK, PushActionKind.DONE -> markRead(payload)
        PushActionKind.REPLY -> reply(payload, reply, onLateFailure)
        PushActionKind.APPROVE -> decide(payload) { apiService.approve(it, "notification") }
        PushActionKind.DENY -> decide(payload) { apiService.deny(it, "notification") }
        // « Je m'y mets » opens the Focus by itself (an activity intent): this only answers the reminder.
        PushActionKind.START, PushActionKind.IN_TEN, PushActionKind.TONIGHT, PushActionKind.TOMORROW ->
            answerReminder(kind, payload) { PushOutcome.Closed }
        PushActionKind.TICK -> answerReminder(kind, payload) { answer ->
            PushOutcome.Rewarded(answer.reward?.message ?: answer.notification?.title ?: TICKED)
        }
    }

    /**
     * Answers [kind] without waiting: « Je m'y mets » opens the app on the Focus, and the answer
     * must not hold the screen nor be lost when the activity goes.
     */
    fun answerInBackground(kind: PushActionKind, payload: PushPayload) {
        sendScope.launch { handle(kind, payload) }
    }

    // In ten minutes and tonight, the server deletes the notification and sends another later;
    // tomorrow and start leave nothing to show either. A refusal is final: the reminder was answered.
    private suspend fun answerReminder(
        kind: PushActionKind,
        payload: PushPayload,
        outcome: (TaskReminderAnswer) -> PushOutcome,
    ): PushOutcome {
        val reminderId = payload.reminderId ?: return PushOutcome.Settled(REMINDER_SETTLED)
        val action = kind.reminderKey ?: return PushOutcome.Settled(REMINDER_SETTLED)
        return try {
            outcome(apiService.answerTaskReminder(reminderId, action))
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiRefusalException) {
            if (e.status in FINAL_STATUSES) PushOutcome.Settled(refusal(kind, e)) else PushOutcome.Retry(UNREACHABLE)
        } catch (e: Exception) {
            Log.w(TAG, "Reminder answer failed: ${e.message}")
            PushOutcome.Retry(UNREACHABLE)
        }
    }

    // The server's reasons are in English: the phone says them in its own words.
    private fun refusal(kind: PushActionKind, e: ApiRefusalException): String = when {
        e.status == 404 -> "Ce rappel n'existe plus."
        e.message.orEmpty().contains("already answered", ignoreCase = true) -> "Ce rappel a déjà eu sa réponse."
        kind == PushActionKind.TONIGHT -> "La soirée est passée : mettez-la plutôt à demain."
        kind == PushActionKind.TOMORROW -> "Demain est déjà plein."
        else -> REMINDER_SETTLED
    }

    private suspend fun markRead(payload: PushPayload): PushOutcome = attempt {
        apiService.markNotificationRead(payload.notificationId)
        PushOutcome.Closed
    }

    // /agent/chat answers once Maggie has finished her turn, which can outlast the few seconds a
    // broadcast receiver may stay alive. The request therefore runs in its own scope: when the
    // budget is spent the message is on its way, so the notification closes rather than ask the
    // owner to type it again (and send it twice).
    private suspend fun reply(payload: PushPayload, text: String?, onLateFailure: (PushOutcome.Retry) -> Unit): PushOutcome {
        val message = text?.trim().orEmpty()
        if (message.isEmpty()) return PushOutcome.Retry("Écrivez votre réponse, puis envoyez-la.")

        val sending = sendScope.async {
            runCatching {
                apiService.sendChat(message)
                // Answered here, so closed everywhere; a failed mark must not undo the send.
                runCatching { apiService.markNotificationRead(payload.notificationId) }
            }
        }
        val result = withTimeoutOrNull(replyBudgetMs) { sending.await() }
        if (result == null) {
            sendScope.launch { sending.await().exceptionOrNull()?.let { onLateFailure(notSent(message, it)) } }
            return PushOutcome.Closed
        }
        return result.exceptionOrNull()?.let { notSent(message, it) } ?: PushOutcome.Closed
    }

    private fun notSent(message: String, error: Throwable): PushOutcome.Retry {
        Log.w(TAG, "Reply failed: ${error.message}")
        return PushOutcome.Retry("$UNREACHABLE Votre réponse n'est pas partie : « $message »")
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

    companion object {
        private const val TAG = "PushActionHandler"
        const val REPLY_BUDGET_MS = 7_000L
        private const val UNREACHABLE = "Maggie est injoignable. Réessayez."
        private const val REMINDER_SETTLED = "Ce rappel n'est plus en attente."
        private const val TICKED = "C'est fait."

        /** The reminder cannot take this answer, now or later: 400 unknown button, 404 gone, 409 refused. */
        private val FINAL_STATUSES = setOf(400, 404, 409)
    }
}

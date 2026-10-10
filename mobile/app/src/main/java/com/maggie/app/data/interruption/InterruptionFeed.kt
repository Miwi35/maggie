package com.maggie.app.data.interruption

import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureEvent
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.mercure.MercureTopics
import com.maggie.app.data.repository.ApprovalRepository
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

/**
 * What reaches the owner while the app is open without a push: the notifications Maggie
 * raises (the same ones FCM announces — the center dedupes them) and the actions she holds
 * for an answer. Run it for as long as the app is in the foreground and signed in.
 */
class InterruptionFeed(
    private val center: InterruptionCenter,
    private val mercure: MercureService,
    private val approvals: ApprovalRepository,
    private val auth: AuthRepository,
) {

    suspend fun run() {
        val userId = auth.getUserId() ?: return
        coroutineScope {
            // Nothing is published for the questions already waiting when the app opens.
            launch {
                approvals.getPending().getOrNull().orEmpty()
                    .mapNotNull { Interruptions.of(it) }
                    .forEach(center::offer)
            }
            launch {
                listen(mercure.subscribe(MercureTopics.userScoped(userId, MercureTopics.NOTIFICATIONS)), Interruptions::readNotification)
            }
            launch {
                listen(mercure.subscribe(MercureTopics.agentScoped(userId, MercureTopics.APPROVALS))) { Interruptions.readApproval(it) }
            }
        }
    }

    private suspend fun listen(events: Flow<MercureEvent>, read: (String) -> FeedEvent?) {
        events
            .catch { /* the stream reconnects by itself */ }
            .collect { event ->
                when (val feed = read(event.data)) {
                    is FeedEvent.Offer -> center.offer(feed.payload)
                    is FeedEvent.Withdraw -> center.close(feed.key)
                    null -> Unit
                }
            }
    }
}

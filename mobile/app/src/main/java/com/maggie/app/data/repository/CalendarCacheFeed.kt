package com.maggie.app.data.repository

import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.mercure.MercureTopics
import com.maggie.app.data.mercure.PushedChanges
import com.maggie.app.data.mercure.batchedAndRechecked
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Keeps the cached calendar current from the pushes (MAG-430): an event or an agenda created,
 * changed or deleted anywhere lands in the cache without the periods being read again. Run for as
 * long as the app is open and signed in — not only while the calendar is on screen, or the cache
 * would be an hour stale after a change announced while the owner was in the chat.
 */
class CalendarCacheFeed(
    private val mercure: MercureService,
    private val events: EventRepository,
    private val agendas: AgendaRepository,
    private val auth: AuthRepository,
) {

    // The sign-in flow re-emits `true` on every token refresh: without the dedupe each refresh
    // would resubscribe and drop what Mercure publishes in the gap.
    suspend fun runWhileSignedIn(signedIn: Flow<Boolean>) {
        signedIn.distinctUntilChanged().collectLatest { if (it) run() }
    }

    suspend fun run() {
        val userId = auth.getUserId() ?: return
        // Nothing was listening while the app was closed or in the background: what was read before
        // may have changed since, so it is read again the next time it is shown.
        events.forgetFetchedPeriods()
        agendas.forgetLastRead()
        coroutineScope {
            launch { listen(userId, MercureTopics.EVENTS) { events.applyPushes(it) } }
            launch {
                listen(userId, MercureTopics.AGENDAS) { pushes ->
                    agendas.applyPushes(pushes).forEach { events.dropEventsOfAgenda(it) }
                }
            }
        }
    }

    private suspend fun listen(userId: String, topic: String, apply: suspend (Map<String, Boolean>) -> Unit) {
        mercure.subscribe(MercureTopics.userScoped(userId, topic))
            .catch { /* the stream reconnects by itself */ }
            .batchedAndRechecked()
            .collect { apply(PushedChanges.of(it)) }
    }
}

package com.maggie.app.data.repository

import com.maggie.app.data.api.ApiRefusalException
import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.mercure.MercureTopics
import com.maggie.app.data.mercure.coalesced
import com.maggie.app.data.model.AgendaHomeEvent
import com.maggie.app.data.model.AgendaHomeState
import com.maggie.app.data.model.HomeDay
import com.maggie.app.util.DateRanges
import com.maggie.app.util.rethrowCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import java.time.LocalDate

/**
 * What the Agenda card of the Accueil reads (MAG-589): the day's events, series already unfolded, and the agendas that
 * name and colour them, from their own resources; and the topics of both, which say the answer moved.
 */
class AgendaCardRepository(
    private val apiService: MaggieApiService,
    private val mercureService: MercureService,
    private val authRepository: AuthRepository,
) {
    /**
     * The card for [day], [today] being the owner's current day; the two reads run together. A read answered 404 (the
     * module is absent) gives a day with no event, which the Accueil does not draw; any other failure is a failure.
     */
    suspend fun read(day: HomeDay, today: LocalDate): Result<AgendaHomeState> {
        val date = if (day == HomeDay.TOMORROW) today.plusDays(1) else today
        return try {
            val range = DateRanges.forDate(date)
            val (events, agendas) = coroutineScope {
                val events = async { apiService.getExpandedEvents(range.start.toString(), range.end.toString()) }
                val agendas = async { apiService.getAgendas() }
                events.await() to agendas.await()
            }
            val byId = agendas.associateBy { it.id }
            Result.success(
                AgendaHomeState(
                    date = date.toString(),
                    events = events
                        .map { AgendaHomeEvent.from(it, byId[it.agenda?.substringAfterLast('/')]) }
                        .sortedWith(AgendaHomeEvent.ORDER),
                ),
            )
        } catch (e: ApiRefusalException) {
            if (e.status == NOT_FOUND) Result.success(AgendaHomeState(date = date.toString())) else Result.failure(e)
        } catch (e: Exception) {
            Result.failure<AgendaHomeState>(e).rethrowCancellation()
        }
    }

    /** One value each time an event or an agenda changes server-side, bursts merged; read [read] again on each. */
    fun changes(): Flow<Unit> = flow {
        val userId = authRepository.getUserId() ?: return@flow
        emitAll(
            listOf(MercureTopics.EVENTS, MercureTopics.AGENDAS)
                .map { mercureService.subscribe(MercureTopics.userScoped(userId, it)).catch { /* SSE reconnects automatically */ } }
                .merge()
                .coalesced()
                .map { },
        )
    }

    private companion object {
        const val NOT_FOUND = 404
    }
}

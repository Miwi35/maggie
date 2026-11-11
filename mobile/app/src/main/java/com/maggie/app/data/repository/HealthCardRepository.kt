package com.maggie.app.data.repository

import com.maggie.app.data.api.ApiRefusalException
import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.mercure.MercureTopics
import com.maggie.app.data.mercure.coalesced
import com.maggie.app.data.model.HealthHomeReads
import com.maggie.app.data.model.HealthHomeState
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
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * What the Santé card of the Accueil reads (MAG-566): the planned session, the steps, the balance, the profile and the
 * fasting window of a day, from the module's own resources, and the topics of everything that feeds them.
 */
class HealthCardRepository(
    private val apiService: MaggieApiService,
    private val mercureService: MercureService,
    private val authRepository: AuthRepository,
) {
    /** The card for [day]; [today] tells whether it is the current day. A part answered 404 (its module is absent) is empty; any other failure is a failure. */
    suspend fun read(day: LocalDate, today: Boolean, now: LocalTime): Result<HealthHomeState> = try {
        val date = day.toString()
        val after = day.atStartOfDay(DateRanges.zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        val before = day.plusDays(1).atStartOfDay(DateRanges.zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        val reads = coroutineScope {
            val workouts = async { whenPresent(emptyList()) { apiService.getPlannedWorkouts(after, before) } }
            val energy = async { if (today) whenPresent(null) { apiService.getDailyEnergy(date, date).firstOrNull() } else null }
            val balance = async { whenPresent(null) { apiService.getDailyBalance(date) } }
            val profile = async { whenPresent(null) { apiService.getHealthProfile() } }
            val fasting = async { whenPresent(null) { apiService.getFasting(date) } }
            HealthHomeReads(workouts.await(), energy.await(), balance.await(), profile.await(), fasting.await())
        }
        Result.success(HealthHomeState.from(today, reads, now, DateRanges.zone))
    } catch (e: Exception) {
        Result.failure<HealthHomeState>(e).rethrowCancellation()
    }

    /** One value each time something the card shows changes server-side, bursts merged; read [read] again on each. */
    fun changes(): Flow<Unit> = flow {
        val userId = authRepository.getUserId() ?: return@flow
        emitAll(
            TOPICS
                .map { mercureService.subscribe(MercureTopics.userScoped(userId, it)).catch { /* SSE reconnects automatically */ } }
                .merge()
                .coalesced()
                .map { },
        )
    }

    private suspend fun <T> whenPresent(absent: T, read: suspend () -> T): T = try {
        read()
    } catch (e: ApiRefusalException) {
        if (e.status == NOT_FOUND) absent else throw e
    }

    private companion object {
        const val NOT_FOUND = 404

        val TOPICS = listOf(
            MercureTopics.WORKOUTS,
            MercureTopics.FOOD_INTAKES,
            MercureTopics.DAILY_ACTIVITIES,
            MercureTopics.HEALTH_PROFILE,
            MercureTopics.HEALTH_DAYS,
        )
    }
}

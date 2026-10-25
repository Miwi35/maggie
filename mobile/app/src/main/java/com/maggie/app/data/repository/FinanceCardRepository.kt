package com.maggie.app.data.repository

import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.mercure.MercureTopics
import com.maggie.app.data.mercure.coalesced
import com.maggie.app.data.model.FinanceHomeState
import com.maggie.app.util.rethrowCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import java.time.LocalDate

/**
 * What the Finance card of the Accueil reads (MAG-592): the Finance dashboard of the current month — one request — and the
 * topics of the resources it is made of.
 */
class FinanceCardRepository(
    private val apiService: MaggieApiService,
    private val mercureService: MercureService,
    private val authRepository: AuthRepository,
    private val today: () -> LocalDate = { LocalDate.now() },
) {
    suspend fun read(): Result<FinanceHomeState> = try {
        val day = today()
        Result.success(FinanceHomeState.from(apiService.getFinanceDashboard(day.year, day.monthValue)))
    } catch (e: Exception) {
        Result.failure<FinanceHomeState>(e).rethrowCancellation()
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

    private companion object {
        // Spending, envelopes and accounts, then the plan the next project comes from.
        val TOPICS = listOf(
            MercureTopics.TRANSACTIONS,
            MercureTopics.ACCOUNTS,
            MercureTopics.ENVELOPES,
            MercureTopics.CATEGORIES,
            MercureTopics.LOANS,
            MercureTopics.SAFETY_CUSHIONS,
            MercureTopics.FINANCE_PROJECTS,
            MercureTopics.FINANCE_SCENARIOS,
            MercureTopics.FINANCE_PLACEMENTS,
        )
    }
}

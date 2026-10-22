package com.maggie.app.data.repository

import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.mercure.MercureTopics
import com.maggie.app.data.mercure.coalesced
import com.maggie.app.data.model.HomeDay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonObject

/**
 * What a card of the Accueil reads (MAG-560): `GET /api/home/{module}?day=today|tomorrow`, and the
 * `home/{module}` topic that says the answer moved. Generic on purpose: every card ticket decodes its own
 * module's [JsonObject], and none of them re-writes the call or the subscription.
 */
class HomeCardRepository(
    private val apiService: MaggieApiService,
    private val mercureService: MercureService,
    private val authRepository: AuthRepository,
) {
    suspend fun state(module: String, day: HomeDay): Result<JsonObject> = runCatching {
        apiService.getHomeCard(module, day.apiValue)
    }

    /** One value each time [module]'s card changes server-side, bursts merged; read [state] again on each. */
    fun changes(module: String): Flow<Unit> = flow {
        val userId = authRepository.getUserId() ?: return@flow
        emitAll(
            mercureService.subscribe(MercureTopics.userScoped(userId, MercureTopics.home(module)))
                .catch { /* SSE reconnects automatically */ }
                .coalesced()
                .map { },
        )
    }
}

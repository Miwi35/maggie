package com.maggie.app.data.repository

import com.maggie.app.data.api.ApiRefusalException
import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.mercure.MercureTopics
import com.maggie.app.data.mercure.coalesced
import com.maggie.app.data.model.GroceryHomeState
import com.maggie.app.data.model.GroceryItem
import com.maggie.app.data.model.GroceryRecipeNeed
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
 * What the Courses card of the Accueil reads (MAG-590): the open list (the lists, then the one that holds the lines) and
 * what the meals of today and tomorrow lack, from their own resources; and the topics of everything that feeds them.
 */
class GroceryCardRepository(
    private val apiService: MaggieApiService,
    private val mercureService: MercureService,
    private val authRepository: AuthRepository,
) {
    /**
     * The card, [today] being the owner's current day. A part answered 404 (its module is absent) is empty, which the
     * Accueil does not draw; any other failure is a failure.
     */
    suspend fun read(today: LocalDate): Result<GroceryHomeState> = try {
        val (items, needs) = coroutineScope {
            val items = async { whenPresent(emptyList<GroceryItem>()) { openList() } }
            val needs = async {
                whenPresent(emptyList<GroceryRecipeNeed>()) {
                    apiService.getGroceryRecipeNeeds(today.toString(), today.plusDays(1).toString())
                }
            }
            items.await() to needs.await()
        }
        Result.success(GroceryHomeState.from(items, needs, today))
    } catch (e: Exception) {
        Result.failure<GroceryHomeState>(e).rethrowCancellation()
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

    private suspend fun openList(): List<GroceryItem> {
        val list = apiService.getGroceryLists().firstOrNull() ?: return emptyList()
        return apiService.getGroceryList(list.id).items
    }

    private suspend fun <T> whenPresent(absent: T, read: suspend () -> T): T = try {
        read()
    } catch (e: ApiRefusalException) {
        if (e.status == NOT_FOUND) absent else throw e
    }

    private companion object {
        const val NOT_FOUND = 404

        // The list, the shops, the products and their aisles, and the meals with the recipes and ingredients that feed the needs.
        val TOPICS = listOf(
            MercureTopics.GROCERY_LISTS,
            MercureTopics.STORES,
            MercureTopics.PRODUCTS,
            MercureTopics.AISLES,
            MercureTopics.MEALS,
            MercureTopics.RECIPES,
            MercureTopics.INGREDIENTS,
        )
    }
}

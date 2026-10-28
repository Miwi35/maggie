package com.maggie.app.data.repository

import com.maggie.app.data.api.ApiRefusalException
import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.mercure.MercureTopics
import com.maggie.app.data.mercure.coalesced
import com.maggie.app.data.model.GroceryRecipeNeed
import com.maggie.app.data.model.Meal
import com.maggie.app.data.model.CuisineHomeState
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
 * What the Cuisine card of the Accueil reads (MAG-591): the meals of a day and what their recipes lack, from their own
 * resources — two requests, whatever the number of meals — and the topics of everything that feeds them.
 */
class CuisineCardRepository(
    private val apiService: MaggieApiService,
    private val mercureService: MercureService,
    private val authRepository: AuthRepository,
) {
    /** The card for [day]. A part answered 404 (its module is absent) is empty; any other failure is a failure. */
    suspend fun read(day: LocalDate): Result<CuisineHomeState> = try {
        val date = day.toString()
        val (meals, needs) = coroutineScope {
            val meals = async { whenPresent(emptyList<Meal>()) { apiService.getMeals(fromDay = date, toDay = date) } }
            val needs = async { whenPresent(emptyList<GroceryRecipeNeed>()) { apiService.getGroceryRecipeNeeds(date, date) } }
            meals.await() to needs.await()
        }
        Result.success(CuisineHomeState.from(meals, needs))
    } catch (e: Exception) {
        Result.failure<CuisineHomeState>(e).rethrowCancellation()
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

        // The meals, their recipes and ingredients, and the products whose stock tells what is missing.
        val TOPICS = listOf(
            MercureTopics.MEALS,
            MercureTopics.RECIPES,
            MercureTopics.INGREDIENTS,
            MercureTopics.PRODUCTS,
        )
    }
}

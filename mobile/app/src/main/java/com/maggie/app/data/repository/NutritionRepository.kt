package com.maggie.app.data.repository

import com.maggie.app.data.api.FoodIntakeWriteRequest
import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.model.DailyBalance
import com.maggie.app.data.model.FastingStatus
import com.maggie.app.data.model.FoodIntake
import com.maggie.app.data.model.HealthDay
import com.maggie.app.data.model.HealthProfile
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Santé › Nutrition (MAG-748): the day's balance, the last days' balances and the food journal; the intermittent fast
 * of the day, the nights' fasts of the last days and the protocol (MAG-770). Days are `YYYY-MM-DD`.
 */
class NutritionRepository(
    private val apiService: MaggieApiService,
) {
    suspend fun getDailyBalance(date: String): Result<DailyBalance> =
        runCatching { apiService.getDailyBalance(date) }

    suspend fun getDailyBalances(from: String, to: String): Result<List<DailyBalance>> =
        runCatching { apiService.getDailyBalances(from, to) }

    suspend fun getFoodIntakes(date: String): Result<List<FoodIntake>> =
        runCatching { apiService.getFoodIntakes(date, date) }

    suspend fun eatPlannedMeal(mealId: String): Result<FoodIntake> =
        runCatching { apiService.eatPlannedMeal(mealId) }

    suspend fun createFoodIntake(request: FoodIntakeWriteRequest): Result<FoodIntake> =
        runCatching { apiService.createFoodIntake(request) }

    suspend fun deleteFoodIntake(id: String): Result<Unit> =
        runCatching { apiService.deleteFoodIntake(id) }

    suspend fun getFasting(date: String): Result<FastingStatus> =
        runCatching { apiService.getFasting(date) }

    /** The journal's days from [from] to [to], for their `nightFastMinutes`: only the days it holds. */
    suspend fun getHealthDays(from: String, to: String): Result<List<HealthDay>> =
        runCatching { apiService.getHealthDays(from, to) }

    /** The protocol (null stops fasting: a JSON null is sent) and the window's start `HH:MM`; the answer is the whole profile. */
    suspend fun updateFasting(protocol: String?, windowStart: String): Result<HealthProfile> =
        runCatching {
            apiService.updateHealthProfile(
                buildJsonObject {
                    put("fastingProtocol", protocol?.let(::JsonPrimitive) ?: JsonNull)
                    put("fastingWindowStart", JsonPrimitive(windowStart))
                },
            )
        }
}

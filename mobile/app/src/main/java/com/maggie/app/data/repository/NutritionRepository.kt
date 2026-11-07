package com.maggie.app.data.repository

import com.maggie.app.data.api.FoodIntakeWriteRequest
import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.model.DailyBalance
import com.maggie.app.data.model.FoodIntake

/** Santé › Nutrition (MAG-748): the day's balance, the last days' balances and the food journal. Days are `YYYY-MM-DD`. */
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
}

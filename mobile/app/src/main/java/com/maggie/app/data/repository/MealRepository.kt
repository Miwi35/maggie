package com.maggie.app.data.repository

import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.api.MealCreateRequest
import com.maggie.app.data.model.Meal

class MealRepository(
    private val apiService: MaggieApiService,
) {
    suspend fun getMeals(startAfter: String? = null, startBefore: String? = null): Result<List<Meal>> = runCatching {
        apiService.getMeals(startAfter = startAfter, startBefore = startBefore)
    }

    suspend fun createMeal(request: MealCreateRequest): Result<Meal> = runCatching {
        apiService.createMeal(request)
    }

    suspend fun deleteMeal(id: String): Result<Unit> = runCatching {
        apiService.deleteMeal(id)
    }
}

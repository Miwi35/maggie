package com.maggie.app.data.repository

import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.api.MealCreateRequest
import com.maggie.app.data.api.MealGroceryItemChoice
import com.maggie.app.data.api.MealGroceryItemsRequest
import com.maggie.app.data.model.Meal
import com.maggie.app.data.model.MealGroceryPreview

class MealRepository(
    private val apiService: MaggieApiService,
) {
    suspend fun getMeals(fromDay: String? = null, toDay: String? = null): Result<List<Meal>> = runCatching {
        apiService.getMeals(fromDay = fromDay, toDay = toDay)
    }

    suspend fun createMeal(request: MealCreateRequest): Result<Meal> = runCatching {
        apiService.createMeal(request)
    }

    suspend fun deleteMeal(id: String): Result<Unit> = runCatching {
        apiService.deleteMeal(id)
    }

    suspend fun groceryPreview(mealId: String): Result<MealGroceryPreview> = runCatching {
        apiService.getMealGroceryPreview(mealId)
    }

    suspend fun addToGroceries(mealId: String, ingredientIds: List<String>): Result<MealGroceryPreview> = runCatching {
        apiService.addMealGroceryItems(
            mealId,
            MealGroceryItemsRequest(ingredientIds.map { MealGroceryItemChoice(it) }),
        )
    }
}

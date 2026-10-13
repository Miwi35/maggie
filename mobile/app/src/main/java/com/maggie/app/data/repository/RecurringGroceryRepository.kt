package com.maggie.app.data.repository

import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.api.RecurringGroceryItemCreateRequest
import com.maggie.app.data.model.RecurringFrequency
import com.maggie.app.data.model.RecurringGroceryItem
import com.maggie.app.data.model.apiValue

class RecurringGroceryRepository(
    private val apiService: MaggieApiService,
) {
    suspend fun getRecurringItems(): Result<List<RecurringGroceryItem>> = runCatching {
        apiService.getRecurringGroceryItems()
    }

    suspend fun createForProduct(productId: String, frequency: RecurringFrequency): Result<RecurringGroceryItem> = runCatching {
        apiService.createRecurringGroceryItem(
            RecurringGroceryItemCreateRequest(product = "/api/products/$productId", frequency = frequency.apiValue()),
        )
    }

    suspend fun updateFrequency(id: String, frequency: RecurringFrequency): Result<RecurringGroceryItem> = runCatching {
        apiService.updateRecurringGroceryItemFrequency(id, frequency)
    }

    suspend fun delete(id: String): Result<Unit> = runCatching {
        apiService.deleteRecurringGroceryItem(id)
    }
}

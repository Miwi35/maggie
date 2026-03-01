package com.maggie.app.data.repository

import com.maggie.app.data.api.AddGroceryItemRequest
import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.model.GroceryList

class GroceryListRepository(
    private val apiService: MaggieApiService,
) {
    suspend fun getGroceryList(): Result<GroceryList?> = runCatching {
        val lists = apiService.getGroceryLists()
        lists.firstOrNull()?.let { apiService.getGroceryList(it.id) }
    }

    suspend fun checkItem(itemId: String, checked: Boolean): Result<Unit> = runCatching {
        apiService.patchGroceryItem(itemId, checked)
    }

    suspend fun deleteItem(itemId: String): Result<Unit> = runCatching {
        apiService.deleteGroceryItem(itemId)
    }

    suspend fun addItem(
        label: String,
        quantity: Float? = null,
        unit: String? = null,
        storeId: String? = null,
        storeName: String? = null,
    ): Result<Unit> = runCatching {
        apiService.addGroceryItem(
            AddGroceryItemRequest(
                label = label,
                quantity = quantity,
                unit = unit,
                storeId = storeId,
                storeName = storeName,
            ),
        )
    }
}

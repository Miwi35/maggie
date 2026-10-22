package com.maggie.app.data.repository

import com.maggie.app.data.api.AddGroceryItemRequest
import com.maggie.app.data.api.EditGroceryItemRequest
import com.maggie.app.data.api.EndErrandRequest
import com.maggie.app.data.api.EndErrandResponse
import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.api.ReorderEntry
import com.maggie.app.data.api.ReorderGroceryItemsRequest
import com.maggie.app.data.model.GroceryList
import com.maggie.app.data.model.PurchaseSuggestion
import com.maggie.app.data.model.RecipeNeedResponse

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
        category: String? = null,
        packagings: Int? = null,
    ): Result<Unit> = runCatching {
        apiService.addGroceryItem(
            AddGroceryItemRequest(
                label = label,
                quantity = quantity,
                unit = unit,
                storeId = storeId,
                storeName = storeName,
                category = category,
                packagings = packagings,
            ),
        )
    }

    /** « Pour les recettes de la semaine »: what the next seven days of meals need of a product (MAG-573). */
    suspend fun getRecipeNeed(productId: String): Result<RecipeNeedResponse> = runCatching {
        apiService.getRecipeNeed(productId)
    }

    suspend fun reorderItems(items: List<ReorderEntry>): Result<Unit> = runCatching {
        apiService.reorderGroceryItems(ReorderGroceryItemsRequest(items = items))
    }

    suspend fun editItem(
        itemId: String,
        label: String? = null,
        quantity: Float? = null,
        unit: String? = null,
        storeId: String? = null,
        storeName: String? = null,
        category: String? = null,
        packagings: Int? = null,
    ): Result<Unit> = runCatching {
        apiService.editGroceryItem(
            itemId,
            EditGroceryItemRequest(
                label = label,
                quantity = quantity,
                unit = unit,
                storeId = storeId,
                storeName = storeName,
                category = category,
                packagings = packagings,
            ),
        )
    }

    /** The products due again by their purchase rhythm, « Souvent pris » (MAG-495). */
    suspend fun getSuggestions(): Result<List<PurchaseSuggestion>> = runCatching {
        apiService.getGrocerySuggestions()
    }

    /** « Pas besoin »: the product is no longer suggested until its next due date. */
    suspend fun snoozeSuggestion(productId: String): Result<Unit> = runCatching {
        apiService.snoozeGrocerySuggestion(productId)
    }

    suspend fun endErrand(storeId: String? = null): Result<EndErrandResponse> = runCatching {
        apiService.endErrand(EndErrandRequest(storeId = storeId))
    }
}

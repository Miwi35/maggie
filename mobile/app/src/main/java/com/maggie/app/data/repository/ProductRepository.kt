package com.maggie.app.data.repository

import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.api.ProductCreateRequest
import com.maggie.app.data.model.Product
import com.maggie.app.data.model.ProductAisle
import kotlinx.serialization.json.JsonObject

class ProductRepository(
    private val apiService: MaggieApiService,
) {
    suspend fun getProducts(): Result<List<Product>> = runCatching {
        apiService.getProducts()
    }

    suspend fun getProduct(id: String): Result<Product> = runCatching {
        apiService.getProduct(id)
    }

    suspend fun createProduct(request: ProductCreateRequest): Result<Product> = runCatching {
        apiService.createProduct(request)
    }

    suspend fun updateSeasonMonths(id: String, months: List<Int>): Result<Product> = runCatching {
        apiService.updateProductSeasonMonths(id, months)
    }

    suspend fun updateProduct(id: String, fields: JsonObject): Result<Product> = runCatching {
        apiService.updateProduct(id, fields)
    }

    suspend fun deleteProduct(id: String): Result<Unit> = runCatching {
        apiService.deleteProduct(id)
    }

    /** Replaces the product's whole aisle list (MAG-493): a store left out has no aisle there. */
    suspend fun updateAisles(id: String, aisles: List<ProductAisle>): Result<Product> = runCatching {
        apiService.updateProductAisles(id, aisles)
    }
}

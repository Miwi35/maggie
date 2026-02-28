package com.maggie.app.data.repository

import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.api.ProductCreateRequest
import com.maggie.app.data.model.Product

class ProductRepository(
    private val apiService: MaggieApiService,
) {
    suspend fun getProducts(): Result<List<Product>> = runCatching {
        apiService.getProducts()
    }

    suspend fun createProduct(request: ProductCreateRequest): Result<Product> = runCatching {
        apiService.createProduct(request)
    }

    suspend fun deleteProduct(id: String): Result<Unit> = runCatching {
        apiService.deleteProduct(id)
    }
}

package com.maggie.app.data.repository

import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.model.Proaction

class ProactionRepository(
    private val apiService: MaggieApiService,
) {
    suspend fun getProactions(): Result<List<Proaction>> = runCatching {
        apiService.getProactions()
    }
}

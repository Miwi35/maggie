package com.maggie.app.data.repository

import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.model.Context

class ContextRepository(
    private val apiService: MaggieApiService,
) {
    suspend fun getContexts(): Result<List<Context>> = runCatching {
        apiService.getContexts()
    }
}

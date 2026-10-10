package com.maggie.app.data.repository

import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.model.Context

class ContextRepository(
    private val apiService: MaggieApiService,
) {
    /** Closed threads included: the list is also where they get cleaned (MAG-342). */
    suspend fun getContexts(includeClosed: Boolean = true): Result<List<Context>> = runCatching {
        apiService.getContexts(includeClosed)
    }

    suspend fun deleteContext(id: String): Result<Unit> = runCatching {
        apiService.deleteContext(id)
    }
}

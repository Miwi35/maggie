package com.maggie.app.data.repository

import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.model.SearchResponse

class SearchRepository(
    private val apiService: MaggieApiService,
) {
    suspend fun search(query: String, page: Int = 1, limit: Int = 10, types: String? = null): Result<SearchResponse> = runCatching {
        apiService.search(query = query, page = page, limit = limit, types = types)
    }
}

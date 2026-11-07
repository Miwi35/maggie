package com.maggie.app.data.repository

import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.model.ProviderStatus

/** Whether Maggie can reach the model (MAG-727). Global to the Anthropic account; nothing is stored here. */
class ProviderStatusRepository(private val apiService: MaggieApiService) {
    suspend fun get(): Result<ProviderStatus> = runCatching { apiService.getProviderStatus() }
}

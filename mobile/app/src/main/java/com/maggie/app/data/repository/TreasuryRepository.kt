package com.maggie.app.data.repository

import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.api.TreasurySettingsRequest
import com.maggie.app.data.model.TreasuryOutlook
import com.maggie.app.data.model.TreasurySettings

class TreasuryRepository(
    private val apiService: MaggieApiService,
) {
    suspend fun getOutlook(): Result<TreasuryOutlook> = runCatching {
        apiService.getTreasuryOutlook()
    }

    suspend fun updateSettings(request: TreasurySettingsRequest): Result<TreasurySettings> = runCatching {
        apiService.updateTreasurySettings(request)
    }
}

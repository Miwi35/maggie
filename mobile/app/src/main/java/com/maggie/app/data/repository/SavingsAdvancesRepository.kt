package com.maggie.app.data.repository

import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.model.SalaryDelay
import com.maggie.app.data.model.SavingsAdvances

class SavingsAdvancesRepository(
    private val apiService: MaggieApiService,
) {
    suspend fun getSavingsAdvances(): Result<SavingsAdvances> = runCatching {
        apiService.getSavingsAdvances()
    }

    /** [day] null forgets the expected day. Gives back the delay recomputed for it. */
    suspend fun setSalaryExpectedDay(day: Int?): Result<SalaryDelay> = runCatching {
        apiService.setSalaryExpectedDay(day).salaryDelay
    }
}

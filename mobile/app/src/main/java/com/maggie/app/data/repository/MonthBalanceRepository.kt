package com.maggie.app.data.repository

import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.model.MonthBalance

class MonthBalanceRepository(
    private val apiService: MaggieApiService,
) {
    suspend fun getBalance(year: Int, month: Int): Result<MonthBalance> = runCatching {
        apiService.getMonthBalance("%04d-%02d".format(year, month))
    }
}

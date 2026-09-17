package com.maggie.app.data.repository

import com.maggie.app.data.api.LoanCreateRequest
import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.model.DebtTimeline
import com.maggie.app.data.model.Loan

class LoanRepository(
    private val apiService: MaggieApiService,
) {
    suspend fun getLoans(): Result<List<Loan>> = runCatching {
        apiService.getLoans()
    }

    suspend fun createLoan(request: LoanCreateRequest): Result<Loan> = runCatching {
        apiService.createLoan(request)
    }

    suspend fun deleteLoan(id: String): Result<Unit> = runCatching {
        apiService.deleteLoan(id)
    }

    suspend fun getTimeline(months: Int = 60): Result<DebtTimeline> = runCatching {
        apiService.getDebtTimeline(months)
    }
}

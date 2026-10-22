package com.maggie.app.data.repository

import com.maggie.app.data.api.LoanCreateRequest
import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.model.DebtTimeline
import com.maggie.app.data.api.LoanTermsRequest
import com.maggie.app.data.model.EarlyRepayment
import com.maggie.app.data.model.Loan
import com.maggie.app.data.model.LoanCurve

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

    suspend fun getLoan(id: String): Result<Loan> = runCatching {
        apiService.getLoan(id)
    }

    suspend fun updateLoan(id: String, terms: LoanTermsRequest): Result<Loan> = runCatching {
        apiService.updateLoan(id, terms)
    }

    suspend fun getCurve(id: String): Result<LoanCurve> = runCatching {
        apiService.getLoanCurve(id)
    }

    suspend fun getEarlyRepayment(id: String, amountCents: Int? = null): Result<EarlyRepayment> = runCatching {
        apiService.getEarlyRepayment(id, amountCents)
    }
}

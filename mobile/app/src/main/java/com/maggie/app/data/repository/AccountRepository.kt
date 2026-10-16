package com.maggie.app.data.repository

import com.maggie.app.data.api.AccountCreateRequest
import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.model.Account
import com.maggie.app.data.model.AccountTimeline

class AccountRepository(
    private val apiService: MaggieApiService,
) {
    suspend fun getAccounts(): Result<List<Account>> = runCatching {
        apiService.getAccounts()
    }

    suspend fun createAccount(request: AccountCreateRequest): Result<Account> = runCatching {
        apiService.createAccount(request)
    }

    suspend fun deleteAccount(id: String): Result<Unit> = runCatching {
        apiService.deleteAccount(id)
    }

    /** One month of an account by day (MAG-422); the current month when [month] is null. */
    suspend fun accountTimeline(accountId: String, month: String? = null): Result<AccountTimeline> = runCatching {
        apiService.getAccountTimeline(accountId, month)
    }

    /** « Corriger le solde »: the new balance of a hand-kept account, in cents. */
    suspend fun updateBalance(accountId: String, balanceCents: Int): Result<Account> = runCatching {
        apiService.updateAccountBalance(accountId, balanceCents)
    }
}

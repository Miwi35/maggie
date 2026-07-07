package com.maggie.app.data.repository

import com.maggie.app.data.api.AccountCreateRequest
import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.model.Account

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
}

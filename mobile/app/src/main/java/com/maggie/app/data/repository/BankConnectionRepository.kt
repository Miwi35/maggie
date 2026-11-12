package com.maggie.app.data.repository

import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.model.BankAuthorization
import com.maggie.app.data.model.Bank
import com.maggie.app.data.model.BankConnection
import com.maggie.app.data.model.BankSyncResult
import com.maggie.app.util.rethrowCancellation

class BankConnectionRepository(
    private val apiService: MaggieApiService,
) {
    suspend fun getConnections(): Result<List<BankConnection>> = runCatching {
        apiService.getBankConnections()
    }.rethrowCancellation()

    suspend fun sync(): Result<BankSyncResult> = runCatching {
        apiService.syncBankConnections()
    }.rethrowCancellation()

    suspend fun reconnect(id: String): Result<BankAuthorization> = runCatching {
        apiService.reconnectBankConnection(id)
    }.rethrowCancellation()

    suspend fun banks(country: String): Result<List<Bank>> = runCatching {
        apiService.getBanks(country).banks
    }.rethrowCancellation()

    suspend fun start(bankName: String, country: String): Result<BankAuthorization> = runCatching {
        apiService.startBankConnection(bankName, country)
    }.rethrowCancellation()

    suspend fun forget(id: String): Result<Unit> = runCatching {
        apiService.forgetBankConnection(id)
    }.rethrowCancellation()
}

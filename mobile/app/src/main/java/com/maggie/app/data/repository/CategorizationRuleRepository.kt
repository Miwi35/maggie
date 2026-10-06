package com.maggie.app.data.repository

import com.maggie.app.data.api.CategorizationRuleCreateRequest
import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.model.AcceptRuleSuggestionsRequest
import com.maggie.app.data.model.AcceptRuleSuggestionsResult
import com.maggie.app.data.model.ApplyRulesResult
import com.maggie.app.data.model.CategorizationRule
import com.maggie.app.data.model.RuleSuggestion
import com.maggie.app.util.rethrowCancellation

class CategorizationRuleRepository(
    private val apiService: MaggieApiService,
) {
    suspend fun getRules(): Result<List<CategorizationRule>> = runCatching {
        apiService.getCategorizationRules()
    }

    suspend fun createRule(request: CategorizationRuleCreateRequest): Result<CategorizationRule> = runCatching {
        apiService.createCategorizationRule(request)
    }

    suspend fun deleteRule(id: String): Result<Unit> = runCatching {
        apiService.deleteCategorizationRule(id)
    }

    suspend fun applyRules(): Result<ApplyRulesResult> = runCatching {
        apiService.applyCategorizationRules()
    }

    suspend fun getSuggestions(): Result<List<RuleSuggestion>> = runCatching {
        apiService.getCategorizationRuleSuggestions()
    }.rethrowCancellation()

    suspend fun acceptSuggestions(
        request: AcceptRuleSuggestionsRequest,
    ): Result<AcceptRuleSuggestionsResult> = runCatching {
        apiService.acceptCategorizationRuleSuggestions(request)
    }.rethrowCancellation()
}

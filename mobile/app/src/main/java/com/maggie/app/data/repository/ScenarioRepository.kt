package com.maggie.app.data.repository

import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.api.ScenarioWriteRequest
import com.maggie.app.data.model.Loan
import com.maggie.app.data.model.Placement
import com.maggie.app.data.model.Project
import com.maggie.app.data.model.Scenario
import com.maggie.app.data.model.ScenarioComparison

/**
 * The scenarios of the projects (MAG-551). A refusal (400, 404) is an `ApiRefusalException` carrying the API's
 * `error`; the projects, loans and placements are what the editor picks from.
 */
class ScenarioRepository(
    private val apiService: MaggieApiService,
) {
    suspend fun getScenarios(): Result<List<Scenario>> = runCatching { apiService.getScenarios().scenarios }

    /** [ids] is 2 to 6 scenarios, the first one being the reference. */
    suspend fun compare(ids: List<String>): Result<ScenarioComparison> = runCatching { apiService.getScenarioComparison(ids) }

    suspend fun create(request: ScenarioWriteRequest): Result<Scenario?> = runCatching { apiService.createScenario(request).scenario }

    suspend fun update(id: String, request: ScenarioWriteRequest): Result<Scenario?> = runCatching { apiService.updateScenario(id, request).scenario }

    suspend fun duplicate(id: String, name: String): Result<Scenario?> = runCatching { apiService.duplicateScenario(id, name).scenario }

    suspend fun makeCurrent(id: String): Result<Unit> = runCatching { apiService.makeScenarioCurrent(id); Unit }

    suspend fun clearCurrent(): Result<Unit> = runCatching { apiService.clearCurrentScenario() }

    suspend fun delete(id: String): Result<Unit> = runCatching { apiService.deleteScenario(id) }

    suspend fun getProjects(): Result<List<Project>> = runCatching { apiService.getProjects().sortedBy { it.position } }

    suspend fun getLoans(): Result<List<Loan>> = runCatching { apiService.getLoans() }

    suspend fun getPlacements(): Result<List<Placement>> = runCatching { apiService.getPlacements() }
}

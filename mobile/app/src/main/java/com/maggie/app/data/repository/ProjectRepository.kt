package com.maggie.app.data.repository

import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.api.ProjectFundingSourceRequest
import com.maggie.app.data.api.ProjectOptionsParams
import com.maggie.app.data.api.ProjectPreviewRequest
import com.maggie.app.data.api.ProjectWriteRequest
import com.maggie.app.data.api.ScenarioWriteRequest
import com.maggie.app.data.model.Account
import com.maggie.app.data.model.Loan
import com.maggie.app.data.model.Placement
import com.maggie.app.data.model.Project
import com.maggie.app.data.model.ProjectPlanning
import com.maggie.app.data.model.ProjectPreview
import com.maggie.app.data.model.ProjectOptions

/** The owner's projects (MAG-519): the list in priority order, its writes, and its order. */
class ProjectRepository(
    private val apiService: MaggieApiService,
) {
    suspend fun getProjects(): Result<List<Project>> = runCatching {
        apiService.getProjects().sortedBy { it.position }
    }

    suspend fun createProject(request: ProjectWriteRequest): Result<Project> = runCatching {
        apiService.createProject(request)
    }

    /** What adding the project would change in the current scenario (or [scenarioId]); nothing is written. */
    suspend fun previewProject(request: ProjectPreviewRequest): Result<ProjectPreview> = runCatching {
        apiService.previewProject(request)
    }

    /** Appends [projectId] to the end of the scenario's projects: the second call of a confirmed add. */
    suspend fun addToScenario(scenarioId: String, projectId: String): Result<Unit> = runCatching {
        val scenario = apiService.getScenarios().scenarios.firstOrNull { it.id == scenarioId }
            ?: error("The scenario no longer exists.")
        apiService.updateScenario(scenarioId, ScenarioWriteRequest.projects(scenario.projectIds + projectId))
        Unit
    }

    suspend fun updateProject(id: String, request: ProjectWriteRequest): Result<Project> = runCatching {
        apiService.updateProject(id, request)
    }

    suspend fun deleteProject(id: String): Result<Unit> = runCatching {
        apiService.deleteProject(id)
    }

    /** [ids] is every project, in the new order; the answer is the list as the API stored it. */
    suspend fun reorderProjects(ids: List<String>): Result<List<Project>> = runCatching {
        apiService.reorderProjects(ids).projects.sortedBy { it.position }
    }

    /** The Gantt's data (MAG-527): projects in priority order, the cushion, the loans. */
    suspend fun getProjectPlanning(): Result<ProjectPlanning> = runCatching {
        apiService.getProjectPlanning().let { planning -> planning.copy(projects = planning.projects.sortedBy { it.position }) }
    }

    /** Replaces the project's sources by [sources]; a refusal is an `ApiRefusalException` with the API's sentence. */
    suspend fun setFunding(id: String, sources: List<ProjectFundingSourceRequest>): Result<Project> = runCatching {
        apiService.setProjectFunding(id, sources).project ?: error("The funding answer carries no project.")
    }

    suspend fun getOptions(id: String, params: ProjectOptionsParams): Result<ProjectOptions> = runCatching {
        apiService.getProjectOptions(id, params)
    }

    /** The accounts a savings or capital source may draw on are picked among these. */
    suspend fun getAccounts(): Result<List<Account>> = runCatching { apiService.getAccounts() }

    /** The placements a capital source may leave (MAG-556), sorted by name. */
    suspend fun getPlacements(): Result<List<Placement>> = runCatching { apiService.getPlacements() }

    /** The projected loan of a loan source, to prefill its conditions. */
    suspend fun getLoan(id: String): Result<Loan> = runCatching { apiService.getLoan(id) }
}

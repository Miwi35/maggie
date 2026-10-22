package com.maggie.app.data.repository

import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.api.ProjectWriteRequest
import com.maggie.app.data.model.Project
import com.maggie.app.data.model.ProjectPlanning

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
}

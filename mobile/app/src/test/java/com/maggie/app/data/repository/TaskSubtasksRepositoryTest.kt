package com.maggie.app.data.repository

import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.api.TaskCreateRequest
import com.maggie.app.data.local.dao.TaskDao
import com.maggie.app.data.model.Task
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

/** What the subtasks ask of the API (MAG-404): their parent's IRI, Google's order, and swapped positions. */
class TaskSubtasksRepositoryTest {
    private val api = mockk<MaggieApiService>()
    private val taskDao = mockk<TaskDao>(relaxed = true)
    private val tasks = TaskRepository(api, taskDao)

    private val first = Task(id = "s1", title = "Réserver le camion", parent = "/api/tasks/p1", position = "00000000000000000000")
    private val second = Task(id = "s2", title = "Changer d'adresse", parent = "/api/tasks/p1", position = "00000000000000000001")

    @Test
    fun `subtasks are asked by their parent's IRI and kept in position order`() = runTest {
        coEvery { api.getSubtasks("/api/tasks/p1") } returns listOf(second, first)

        assertEquals(listOf("s1", "s2"), tasks.getSubtasks("p1").getOrThrow().map { it.id })
        coVerify { taskDao.upsertAll(match { it.size == 2 }) }
    }

    @Test
    fun `a new subtask is posted with its parent's IRI and no position`() = runTest {
        coEvery { api.createTask(any()) } returns first

        tasks.createSubtask("p1", "Réserver le camion")

        coVerify { api.createTask(TaskCreateRequest(title = "Réserver le camion", parent = "/api/tasks/p1")) }
    }

    @Test
    fun `moving swaps the two positions with two patches`() = runTest {
        coEvery { api.updateTask("s1", any()) } returns first.copy(position = second.position)
        coEvery { api.updateTask("s2", any()) } returns second.copy(position = first.position)

        val moved = tasks.swapPositions(first, second).getOrThrow()

        coVerify { api.updateTask("s1", buildJsonObject { put("position", "00000000000000000001") }) }
        coVerify { api.updateTask("s2", buildJsonObject { put("position", "00000000000000000000") }) }
        assertEquals(listOf("00000000000000000001", "00000000000000000000"), moved.map { it.position })
    }
}

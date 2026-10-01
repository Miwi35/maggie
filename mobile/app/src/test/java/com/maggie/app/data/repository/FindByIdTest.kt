package com.maggie.app.data.repository

import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.local.dao.EventDao
import com.maggie.app.data.local.dao.TaskDao
import com.maggie.app.data.local.entity.EventEntity
import com.maggie.app.data.local.entity.TaskEntity
import com.maggie.app.data.model.Event
import com.maggie.app.data.model.Task
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// What a deep link needs from the data layer: the cached row, else the server's, else nothing.
class FindByIdTest {
    private val api = mockk<MaggieApiService>()
    private val eventDao = mockk<EventDao>(relaxed = true)
    private val taskDao = mockk<TaskDao>(relaxed = true)
    private val events = EventRepository(api, eventDao)
    private val tasks = TaskRepository(api, taskDao)

    private val event = Event(id = "e1", summary = "Dentiste", startAt = "2026-10-05T08:00:00Z", endAt = "2026-10-05T09:00:00Z")
    private val task = Task(id = "t1", title = "Payer la cantine")

    @Test
    fun `a cached event is returned without calling the server`() = runTest {
        coEvery { eventDao.getById("e1") } returns EventEntity.fromModel(event)

        assertEquals(event, events.findEvent("e1"))
        coVerify(exactly = 0) { api.getEvent(any()) }
    }

    @Test
    fun `an event missing from the cache is fetched and cached`() = runTest {
        coEvery { eventDao.getById("e1") } returns null
        coEvery { api.getEvent("e1") } returns event

        assertEquals(event, events.findEvent("e1"))
        coVerify { eventDao.upsertAll(match { it.single().id == "e1" }) }
    }

    @Test
    fun `an event nobody has is null, not an exception`() = runTest {
        coEvery { eventDao.getById("gone") } returns null
        coEvery { api.getEvent("gone") } throws RuntimeException("404")

        assertNull(events.findEvent("gone"))
        coVerify(exactly = 0) { eventDao.upsertAll(any()) }
    }

    @Test
    fun `a cached task is returned without calling the server`() = runTest {
        coEvery { taskDao.getById("t1") } returns TaskEntity.fromModel(task)

        assertEquals(task, tasks.findTask("t1"))
        coVerify(exactly = 0) { api.getTask(any()) }
    }

    @Test
    fun `a task missing from the cache is fetched and cached`() = runTest {
        coEvery { taskDao.getById("t1") } returns null
        coEvery { api.getTask("t1") } returns task

        assertEquals(task, tasks.findTask("t1"))
        coVerify { taskDao.upsertAll(match { it.single().id == "t1" }) }
    }

    @Test
    fun `a task nobody has is null, not an exception`() = runTest {
        coEvery { taskDao.getById("gone") } returns null
        coEvery { api.getTask("gone") } throws RuntimeException("404")

        assertNull(tasks.findTask("gone"))
    }

    @Test
    fun `a cancelled lookup is cancelled, not reported as not found`() = runTest {
        coEvery { eventDao.getById("e1") } returns null
        coEvery { api.getEvent("e1") } throws CancellationException("scope gone")
        coEvery { taskDao.getById("t1") } returns null
        coEvery { api.getTask("t1") } throws CancellationException("scope gone")

        try {
            events.findEvent("e1")
            throw AssertionError("findEvent swallowed the cancellation")
        } catch (_: CancellationException) {
        }
        try {
            tasks.findTask("t1")
            throw AssertionError("findTask swallowed the cancellation")
        } catch (_: CancellationException) {
        }
    }
}

package com.maggie.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.maggie.app.data.model.Task
import com.maggie.app.data.model.TaskLink

@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey
    val id: String,
    val title: String,
    val description: String?,
    val priority: String,
    val criticality: String,
    /** `YYYY-MM-DD`: a day sorts and compares as a string. */
    val dueDate: String?,
    val completedAt: String?,
    val parent: String? = null,
    val position: String? = null,
    val links: List<TaskLink> = emptyList(),
    val estimatedMinutes: Int? = null,
    val firstStep: String? = null,
    val syncStatus: SyncStatus = SyncStatus.SYNCED,
) {
    fun toModel(): Task = Task(
        id = id,
        title = title,
        description = description,
        priority = priority,
        criticality = criticality,
        dueDate = dueDate,
        completedAt = completedAt,
        parent = parent,
        position = position,
        links = links,
        estimatedMinutes = estimatedMinutes,
        firstStep = firstStep,
    )

    companion object {
        fun fromModel(task: Task, syncStatus: SyncStatus = SyncStatus.SYNCED): TaskEntity =
            TaskEntity(
                id = task.id,
                title = task.title,
                description = task.description,
                priority = task.priority,
                criticality = task.criticality,
                dueDate = task.dueDate,
                completedAt = task.completedAt,
                parent = task.parent,
                position = task.position,
                links = task.links,
                estimatedMinutes = task.estimatedMinutes,
                firstStep = task.firstStep,
                syncStatus = syncStatus,
            )
    }
}

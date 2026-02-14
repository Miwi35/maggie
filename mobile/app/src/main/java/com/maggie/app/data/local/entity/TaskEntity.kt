package com.maggie.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.maggie.app.data.model.Task

@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey
    val id: String,
    val title: String,
    val description: String?,
    val priority: String,
    val criticality: String,
    val dueDate: String?,
    val completedAt: String?,
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
                syncStatus = syncStatus,
            )
    }
}

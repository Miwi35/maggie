package com.maggie.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.maggie.app.data.model.Task

@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val description: String?,
    val priority: String,
    val criticality: String,
    val dueDate: String?,
    val doneDate: String?,
    val syncStatus: SyncStatus = SyncStatus.SYNCED,
) {
    fun toModel(): Task = Task(
        id = id,
        name = name,
        description = description,
        priority = priority,
        criticality = criticality,
        dueDate = dueDate,
        doneDate = doneDate,
    )

    companion object {
        fun fromModel(task: Task, syncStatus: SyncStatus = SyncStatus.SYNCED): TaskEntity =
            TaskEntity(
                id = task.id,
                name = task.name,
                description = task.description,
                priority = task.priority,
                criticality = task.criticality,
                dueDate = task.dueDate,
                doneDate = task.doneDate,
                syncStatus = syncStatus,
            )
    }
}

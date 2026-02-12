package com.maggie.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.maggie.app.data.local.dao.ChatMessageDao
import com.maggie.app.data.local.dao.EventDao
import com.maggie.app.data.local.dao.TaskDao
import com.maggie.app.data.local.entity.ChatMessageEntity
import com.maggie.app.data.local.entity.EventEntity
import com.maggie.app.data.local.entity.TaskEntity

@Database(
    entities = [EventEntity::class, ChatMessageEntity::class, TaskEntity::class],
    version = 2,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class MaggieDatabase : RoomDatabase() {
    abstract fun eventDao(): EventDao
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun taskDao(): TaskDao
}

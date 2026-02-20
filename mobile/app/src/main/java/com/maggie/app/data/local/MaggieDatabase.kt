package com.maggie.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.maggie.app.data.local.dao.AgendaDao
import com.maggie.app.data.local.dao.ChatMessageDao
import com.maggie.app.data.local.dao.EventDao
import com.maggie.app.data.local.dao.RecipeDao
import com.maggie.app.data.local.dao.TaskDao
import com.maggie.app.data.local.entity.AgendaEntity
import com.maggie.app.data.local.entity.ChatMessageEntity
import com.maggie.app.data.local.entity.EventEntity
import com.maggie.app.data.local.entity.RecipeEntity
import com.maggie.app.data.local.entity.TaskEntity

@Database(
    entities = [EventEntity::class, ChatMessageEntity::class, TaskEntity::class, AgendaEntity::class, RecipeEntity::class],
    version = 7,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class MaggieDatabase : RoomDatabase() {
    abstract fun eventDao(): EventDao
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun taskDao(): TaskDao
    abstract fun agendaDao(): AgendaDao
    abstract fun recipeDao(): RecipeDao
}

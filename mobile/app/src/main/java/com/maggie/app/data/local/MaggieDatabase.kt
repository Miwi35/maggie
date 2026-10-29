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
import com.maggie.app.data.local.entity.FetchedPeriodEntity
import com.maggie.app.data.local.entity.RecipeEntity
import com.maggie.app.data.local.entity.TaskEntity

@Database(
    entities = [
        EventEntity::class,
        FetchedPeriodEntity::class,
        ChatMessageEntity::class,
        TaskEntity::class,
        AgendaEntity::class,
        RecipeEntity::class,
    ],
    // 8: events carry their reminders (MAG-121). The database is a cache of the
    // API and the builder falls back to a destructive migration, so a bump is all
    // a new column needs.
    // 9: an all-day event is a pair of dates, its instants null (MAG-382).
    // 10: chat messages say whether a screenshot went with them (MAG-214).
    // 11: messages carry their thread (contextId, MAG-342), to drop a deleted thread's messages.
    // 12: the stretches of calendar already read, with the time of the read (MAG-430).
    // 13: tasks carry their parent, position and links, and the due date is a day (MAG-404).
    // 14: answers carry the owner's thumb and its comment (MAG-23).
    // 15: tasks carry their estimated duration and first step (MAG-497).
    // 16: a voice message carries its audio attachment (MAG-686).
    version = 16,
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

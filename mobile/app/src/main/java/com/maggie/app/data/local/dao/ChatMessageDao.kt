package com.maggie.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.maggie.app.data.local.entity.ChatMessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatMessageDao {

    @Query("SELECT * FROM chat_messages ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM chat_messages ORDER BY createdAt ASC")
    suspend fun getAll(): List<ChatMessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(message: ChatMessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(messages: List<ChatMessageEntity>)

    @Query("SELECT MAX(createdAt) FROM chat_messages")
    suspend fun getLatestTimestamp(): String?

    @Query("DELETE FROM chat_messages")
    suspend fun deleteAll()
}

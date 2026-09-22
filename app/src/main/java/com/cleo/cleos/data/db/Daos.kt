package com.cleo.cleos.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    fun observe(id: Long): Flow<ConversationEntity?>

    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC LIMIT 1")
    suspend fun latest(): ConversationEntity?

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun get(id: Long): ConversationEntity?

    @Insert
    suspend fun insert(conversation: ConversationEntity): Long

    @Query("UPDATE conversations SET updatedAt = :at WHERE id = :id")
    suspend fun touch(id: Long, at: Long)

    @Query("UPDATE conversations SET title = :title WHERE id = :id")
    suspend fun rename(id: Long, title: String)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM conversations")
    suspend fun all(): List<ConversationEntity>

    @Insert
    suspend fun insertAll(items: List<ConversationEntity>)

    @Query("DELETE FROM conversations")
    suspend fun clear()
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt, id")
    fun observe(conversationId: Long): Flow<List<MessageEntity>>

    /** The newest [limit] messages, newest first. Callers reverse them for the API. */
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt DESC, id DESC LIMIT :limit")
    suspend fun newest(conversationId: Long, limit: Int): List<MessageEntity>

    @Query("SELECT COUNT(*) FROM messages WHERE conversationId = :conversationId")
    suspend fun count(conversationId: Long): Int

    @Insert
    suspend fun insert(message: MessageEntity): Long

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM messages")
    suspend fun all(): List<MessageEntity>

    @Insert
    suspend fun insertAll(items: List<MessageEntity>)

    @Query("DELETE FROM messages")
    suspend fun clear()
}

@Dao
interface DiaryDao {
    @Query("SELECT * FROM diary_entries ORDER BY day DESC, createdAt DESC")
    fun observeAll(): Flow<List<DiaryEntryEntity>>

    @Query("SELECT * FROM diary_entries WHERE id = :id")
    suspend fun get(id: Long): DiaryEntryEntity?

    @Insert
    suspend fun insert(entry: DiaryEntryEntity): Long

    @Update
    suspend fun update(entry: DiaryEntryEntity)

    @Query("DELETE FROM diary_entries WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM diary_entries")
    suspend fun all(): List<DiaryEntryEntity>

    @Insert
    suspend fun insertAll(items: List<DiaryEntryEntity>)

    @Query("DELETE FROM diary_entries")
    suspend fun clear()
}

@Dao
interface TodoDao {
    /** Unordered: the screen sorts (dated first by date, then the rest in the order added). */
    @Query("SELECT * FROM todos")
    fun observeAll(): Flow<List<TodoEntity>>

    @Insert
    suspend fun insert(todo: TodoEntity): Long

    @Upsert
    suspend fun upsert(todo: TodoEntity)

    @Delete
    suspend fun delete(todo: TodoEntity)

    @Query("SELECT * FROM todos")
    suspend fun all(): List<TodoEntity>

    @Insert
    suspend fun insertAll(items: List<TodoEntity>)

    @Query("DELETE FROM todos")
    suspend fun clear()
}

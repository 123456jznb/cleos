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

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun get(id: Long): MessageEntity?

    /** The pictures of one conversation's messages, to delete the files along with it. */
    @Query("SELECT images FROM messages WHERE conversationId = :conversationId AND images IS NOT NULL")
    suspend fun imagesIn(conversationId: Long): List<String>

    /** The person's latest message with pictures in a conversation: what "this photo" means. */
    @Query(
        "SELECT * FROM messages WHERE conversationId = :conversationId AND role = 'user' AND images IS NOT NULL " +
            "ORDER BY createdAt DESC, id DESC LIMIT 1",
    )
    suspend fun latestWithImages(conversationId: Long): MessageEntity?

    // For the home page: the first thing the person said, and how much was said. Lines
    // shown instead of bubbles (notes, answers to requests) are not things said.

    @Query("SELECT MIN(createdAt) FROM messages WHERE role = 'user' AND note IS NULL")
    fun observeFirstSaid(): Flow<Long?>

    @Query(
        "SELECT COUNT(*) FROM messages WHERE role IN ('user', 'assistant') AND note IS NULL " +
            "AND error IS NULL AND content != ''",
    )
    fun observeSaidCount(): Flow<Int>

    /** Every request to see a secret, in any conversation, oldest first. */
    @Query("SELECT * FROM messages WHERE role = 'request' ORDER BY createdAt, id")
    suspend fun requests(): List<MessageEntity>

    @Query("UPDATE messages SET content = :content WHERE id = :id")
    suspend fun setContent(id: Long, content: String)

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

    @Query("SELECT COUNT(*) FROM diary_entries")
    fun observeCount(): Flow<Int>

    // What the model may read: never a secret, and only entries by the [authors] it is
    // allowed (its own, and the person's when reading the diary is switched on).

    @Query("SELECT * FROM diary_entries WHERE day = :day AND secret = 0 AND author IN (:authors) ORDER BY createdAt")
    suspend fun onDay(day: Long, authors: List<String>): List<DiaryEntryEntity>

    @Query(
        "SELECT * FROM diary_entries WHERE secret = 0 AND author IN (:authors) " +
            "ORDER BY day DESC, createdAt DESC LIMIT :limit",
    )
    suspend fun recent(authors: List<String>, limit: Int): List<DiaryEntryEntity>

    /**
     * Candidates for a keyword, newest first. [pattern] is a LIKE pattern with `!` as the
     * escape character. It also matches inside the blocks' JSON (keys, image file names),
     * so callers check the plain text again.
     */
    @Query(
        "SELECT * FROM diary_entries WHERE secret = 0 AND author IN (:authors) " +
            "AND (title LIKE :pattern ESCAPE '!' OR blocks LIKE :pattern ESCAPE '!') " +
            "ORDER BY day DESC, createdAt DESC LIMIT :limit",
    )
    suspend fun search(pattern: String, authors: List<String>, limit: Int): List<DiaryEntryEntity>

    @Query("SELECT * FROM diary_entries WHERE secret = 1 ORDER BY day DESC, createdAt DESC")
    suspend fun secrets(): List<DiaryEntryEntity>

    @Query("SELECT COUNT(*) FROM diary_entries WHERE secret = 1 AND day = :day")
    suspend fun secretsOnDay(day: Long): Int

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

    @Query("SELECT * FROM todos WHERE id = :id")
    suspend fun get(id: Long): TodoEntity?

    @Query("SELECT COUNT(*) FROM todos WHERE done = 1")
    fun observeDoneCount(): Flow<Int>

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

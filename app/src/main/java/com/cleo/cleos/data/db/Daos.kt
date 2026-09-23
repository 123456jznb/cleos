package com.cleo.cleos.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface CompanionDao {
    @Query("SELECT * FROM companions ORDER BY createdAt, id")
    fun observeAll(): Flow<List<CompanionEntity>>

    @Query("SELECT * FROM companions ORDER BY createdAt, id")
    suspend fun all(): List<CompanionEntity>

    @Query("SELECT * FROM companions WHERE id = :id")
    suspend fun get(id: Long): CompanionEntity?

    @Insert
    suspend fun insert(companion: CompanionEntity): Long

    @Update
    suspend fun update(companion: CompanionEntity)

    @Query("DELETE FROM companions WHERE id = :id")
    suspend fun delete(id: Long)

    @Insert
    suspend fun insertAll(items: List<CompanionEntity>)

    @Query("DELETE FROM companions")
    suspend fun clear()
}

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations WHERE companionId = :companionId ORDER BY updatedAt DESC")
    fun observeFor(companionId: Long): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE companionId = :companionId ORDER BY updatedAt DESC LIMIT 1")
    suspend fun latestFor(companionId: Long): ConversationEntity?

    @Query("SELECT id FROM conversations WHERE companionId = :companionId")
    suspend fun idsFor(companionId: Long): List<Long>

    @Query("SELECT * FROM conversations WHERE id = :id")
    fun observe(id: Long): Flow<ConversationEntity?>

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

    // For the home page, with one TA: the first thing the person said to them, and how
    // much was said. Lines shown instead of bubbles (notes, answers) are not things said.

    @Query(
        "SELECT MIN(m.createdAt) FROM messages m JOIN conversations c ON c.id = m.conversationId " +
            "WHERE c.companionId = :companionId AND m.role = 'user' AND m.note IS NULL",
    )
    fun observeFirstSaid(companionId: Long): Flow<Long?>

    @Query(
        "SELECT COUNT(*) FROM messages m JOIN conversations c ON c.id = m.conversationId " +
            "WHERE c.companionId = :companionId AND m.role IN ('user', 'assistant') AND m.note IS NULL " +
            "AND m.error IS NULL AND m.content != ''",
    )
    fun observeSaidCount(companionId: Long): Flow<Int>

    /** One TA's requests to see a secret, from any of their conversations, oldest first. */
    @Query(
        "SELECT m.* FROM messages m JOIN conversations c ON c.id = m.conversationId " +
            "WHERE c.companionId = :companionId AND m.role = 'request' ORDER BY m.createdAt, m.id",
    )
    suspend fun requestsBy(companionId: Long): List<MessageEntity>

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

    /** How many entries one TA has written. */
    @Query("SELECT COUNT(*) FROM diary_entries WHERE author = 'ai' AND companionId = :companionId")
    fun observeWrittenBy(companionId: Long): Flow<Int>

    @Query("DELETE FROM diary_entries WHERE author = 'ai' AND companionId = :companionId")
    suspend fun deleteWrittenBy(companionId: Long)

    // What a TA may read: never a secret; the person's entries when [mine] (reading the
    // diary is switched on); of the TAs' entries only [own]'s, pass -1 for none. Another
    // TA's diary is never among them.

    @Query(
        "SELECT * FROM diary_entries WHERE day = :day AND secret = 0 " +
            "AND ((:mine AND author = 'me') OR (author = 'ai' AND companionId = :own)) ORDER BY createdAt",
    )
    suspend fun onDay(day: Long, mine: Boolean, own: Long): List<DiaryEntryEntity>

    @Query(
        "SELECT * FROM diary_entries WHERE secret = 0 " +
            "AND ((:mine AND author = 'me') OR (author = 'ai' AND companionId = :own)) " +
            "ORDER BY day DESC, createdAt DESC LIMIT :limit",
    )
    suspend fun recent(mine: Boolean, own: Long, limit: Int): List<DiaryEntryEntity>

    /**
     * Candidates for a keyword, newest first. [pattern] is a LIKE pattern with `!` as the
     * escape character. It also matches inside the blocks' JSON (keys, image file names),
     * so callers check the plain text again.
     */
    @Query(
        "SELECT * FROM diary_entries WHERE secret = 0 " +
            "AND ((:mine AND author = 'me') OR (author = 'ai' AND companionId = :own)) " +
            "AND (title LIKE :pattern ESCAPE '!' OR blocks LIKE :pattern ESCAPE '!') " +
            "ORDER BY day DESC, createdAt DESC LIMIT :limit",
    )
    suspend fun search(pattern: String, mine: Boolean, own: Long, limit: Int): List<DiaryEntryEntity>

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

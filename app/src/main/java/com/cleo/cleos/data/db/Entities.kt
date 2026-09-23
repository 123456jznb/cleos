package com.cleo.cleos.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
@Entity(
    tableName = "messages",
    indices = [Index("conversationId")],
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    /**
     * "user", "assistant", "tool" (what a tool call returned, answering [toolCallId]) or
     * "note" (a line shown in the chat that is never sent to the model).
     */
    val role: String,
    /** For "tool": the result exactly as the model saw it. */
    val content: String,
    val createdAt: Long,
    /**
     * Set when this assistant turn did not finish (network error, stopped by hand).
     * The partial text is kept rather than thrown away: it may be the part worth reading.
     */
    val error: String? = null,
    /** Assistant turns that called tools: JSON array of ToolCall. */
    val toolCalls: String? = null,
    /**
     * Reasoning streamed before those calls. Providers that stream reasoning want it
     * back while the same turn is still calling tools (DeepSeek rejects the request
     * without it), so it is kept for turns with calls and only for those.
     */
    val reasoning: String? = null,
    /** "tool": the call this answers. */
    val toolCallId: String? = null,
    /** "tool" and "note": the one line the chat shows, e.g. 记下了待办「交报告」. */
    val note: String? = null,
)

@Serializable
@Entity(tableName = "diary_entries", indices = [Index("day")])
data class DiaryEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** LocalDate.toEpochDay(): the day the entry is about, which the writer can change. */
    val day: Long,
    val title: String,
    /** JSON array of DiaryBlock: text and images in the order they were written. */
    val blocks: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
@Entity(tableName = "todos")
data class TodoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val note: String = "",
    val done: Boolean = false,
    /** LocalDate.toEpochDay(), or null for no date. */
    val dueDay: Long? = null,
    val createdAt: Long,
    val doneAt: Long? = null,
)

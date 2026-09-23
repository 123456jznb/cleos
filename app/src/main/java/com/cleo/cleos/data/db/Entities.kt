package com.cleo.cleos.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * One TA: who they are and which model speaks for them. Each has their own conversations
 * and their own diary entries; they don't see each other's.
 */
@Serializable
@Entity(tableName = "companions")
data class CompanionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String = "",
    /** Written by the person, sent as-is. Empty means no persona at all. */
    val persona: String = "",
    val apiBaseUrl: String,
    val apiModel: String,
    /** A picture in ImageStore; or an emoji TA picked for itself; neither means the initial. */
    val avatar: String? = null,
    val avatarEmoji: String? = null,
    /** LocalDate.toEpochDay() the home page counts from; null counts from the first message. */
    val knownSince: Long? = null,
    val createdAt: Long,
)

@Serializable
@Entity(tableName = "conversations", indices = [Index("companionId")])
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    /** Whose conversation it is. Everything from before there were several TAs is TA 1's. */
    @ColumnInfo(defaultValue = "1")
    val companionId: Long = 1,
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
     * "user", "assistant", "tool" (what a tool call returned, answering [toolCallId]),
     * "note" (a line shown in the chat that is never sent to the model) or "request" (the
     * model asking to see a little secret; [content] is a SecretRequest as JSON, and the
     * card is for the person only).
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
    /**
     * The one line the chat shows instead of a bubble: for "tool" and "note" rows
     * (记下了待办「交报告」), and for a "user" row that is an answer to a request.
     */
    val note: String? = null,
    /** "user": the pictures sent with it, a JSON array of MessageImage, in order. */
    val images: String? = null,
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
    /** [AUTHOR_ME] or [AUTHOR_AI]: the book is shared, each entry says whose it is. */
    @ColumnInfo(defaultValue = DiaryEntryEntity.AUTHOR_ME)
    val author: String = DiaryEntryEntity.AUTHOR_ME,
    /**
     * A little secret: the model never reads it. It can only ask, in the chat, and sees it
     * when the person says yes, that one time.
     */
    @ColumnInfo(defaultValue = "0")
    val secret: Boolean = false,
    /** For [AUTHOR_AI]: which TA wrote it. Null for the person's own entries. */
    val companionId: Long? = null,
) {
    companion object {
        const val AUTHOR_ME = "me"
        const val AUTHOR_AI = "ai"
    }
}

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

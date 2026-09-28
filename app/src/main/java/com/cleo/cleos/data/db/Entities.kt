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
    /** When this TA last tried to write a letter of their own, written or not: the wait between tries counts from it. */
    val lastLetterTry: Long? = null,
    /** Asks the model to think before it answers (the switch DeepSeek and GLM take). */
    @ColumnInfo(defaultValue = "0")
    val deepThinking: Boolean = false,
)

/**
 * Something one TA keeps in mind, about the person or about themselves: a topic, not a
 * single fact. Its one-line [summary] goes with every message; the [details] only when the
 * TA opens it. Only that TA has it.
 */
@Serializable
@Entity(tableName = "memories", indices = [Index("companionId")])
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val companionId: Long,
    /** One of MemoryKinds: profile, interest, recent, rapport, self. */
    val kind: String,
    /** A short title: 怎么称呼, 读书口味. */
    val name: String,
    /** What the topic is about, in one line. */
    val summary: String,
    /** A JSON array of strings (MemoryDetails). */
    val details: String = "[]",
    /** Pinned by the person: the TA can't change or delete it. */
    val pinned: Boolean = false,
    /** Who wrote it: [SOURCE_AI], [SOURCE_ME], or [SOURCE_IMPORT]. */
    val source: String = SOURCE_AI,
    val createdAt: Long,
    val updatedAt: Long,
) {
    companion object {
        const val SOURCE_AI = "ai"
        const val SOURCE_ME = "me"
        const val SOURCE_IMPORT = "import"
    }
}

/**
 * A letter between the person and one TA. Theirs alone: other TAs don't see it.
 *
 * The person's start as drafts and are sent once, then stay as sent. A TA's is written
 * ahead of time and appears at [deliverAt], a while later, the way letters arrive.
 */
@Serializable
@Entity(tableName = "letters", indices = [Index("companionId")])
data class LetterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val companionId: Long,
    /** [AUTHOR_ME] or [AUTHOR_AI]. */
    val author: String,
    val content: String,
    /** Written (a TA's) or last edited (a draft); for a sent letter, when it was sent. */
    val createdAt: Long,
    /** A TA's letter shows from then on. The person's: when it was sent, null while a draft. */
    val deliverAt: Long? = null,
    /** When the person opened a TA's letter; null while it is unread. */
    val readAt: Long? = null,
    /** For a TA's reply: the person's letter it answers. */
    val replyTo: Long? = null,
    /** For the person's sent letter: when its reply arrives, as they picked on sending. */
    val replyDueAt: Long? = null,
) {
    val draft: Boolean get() = author == AUTHOR_ME && deliverAt == null

    companion object {
        const val AUTHOR_ME = "me"
        const val AUTHOR_AI = "ai"
    }
}

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
    /** What the TA keeps of the messages no longer sent verbatim: a running summary (ai/Recap.kt). */
    val recap: String? = null,
    /** The last message folded into [recap], by the order messages are read in: its time, then its id. */
    val recapUntilAt: Long? = null,
    val recapUntilId: Long? = null,
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
    /** "user": a voice message's recording (MessageAudio as JSON); [content] is what it said, once transcribed. */
    val audio: String? = null,
    /**
     * "assistant": what the TA thought before this (MessageThought as JSON), shown folded above
     * it. Unlike [reasoning] it is never sent back: it is kept to be read.
     */
    val thought: String? = null,
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

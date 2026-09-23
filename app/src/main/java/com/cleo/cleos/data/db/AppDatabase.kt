package com.cleo.cleos.data.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Schema changes must come with a Migration. Never fall back to destructive migration:
 * this database holds a diary, and "the app updated and my diary is empty" is the one
 * failure this app cannot have.
 */
@Database(
    entities = [ConversationEntity::class, MessageEntity::class, DiaryEntryEntity::class, TodoEntity::class],
    version = 3,
    exportSchema = true,
    autoMigrations = [
        // 1 -> 2: tool calls on messages (four nullable columns, nothing rewritten).
        AutoMigration(from = 1, to = 2),
        // 2 -> 3: who wrote a diary entry, and whether it is a secret (both with defaults).
        AutoMigration(from = 2, to = 3),
    ],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversations(): ConversationDao
    abstract fun messages(): MessageDao
    abstract fun diary(): DiaryDao
    abstract fun todos(): TodoDao
}

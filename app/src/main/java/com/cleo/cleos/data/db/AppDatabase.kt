package com.cleo.cleos.data.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Schema changes must come with a Migration. Never fall back to destructive migration:
 * this database holds a diary, and "the app updated and my diary is empty" is the one
 * failure this app cannot have.
 */
@Database(
    entities = [
        CompanionEntity::class,
        ConversationEntity::class,
        MessageEntity::class,
        DiaryEntryEntity::class,
        TodoEntity::class,
    ],
    version = 5,
    exportSchema = true,
    autoMigrations = [
        // 1 -> 2: tool calls on messages (four nullable columns, nothing rewritten).
        AutoMigration(from = 1, to = 2),
        // 2 -> 3: who wrote a diary entry, and whether it is a secret (both with defaults).
        AutoMigration(from = 2, to = 3),
        // 3 -> 4: pictures sent in the chat (one nullable column).
        AutoMigration(from = 3, to = 4),
        // 4 -> 5: several TAs. Their table; whose each conversation and TA diary entry is.
        // The first TA's row is made at startup from the old settings (Companions.ensure).
        AutoMigration(from = 4, to = 5, spec = AppDatabase.OneTaBefore::class),
    ],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun companions(): CompanionDao
    abstract fun conversations(): ConversationDao
    abstract fun messages(): MessageDao
    abstract fun diary(): DiaryDao
    abstract fun todos(): TodoDao

    /**
     * Before version 5 there was one TA, so every entry a TA wrote was TA 1's. (Conversations
     * get that from the column's default; a diary entry's owner depends on its author.)
     */
    class OneTaBefore : AutoMigrationSpec {
        override fun onPostMigrate(db: SupportSQLiteDatabase) {
            db.execSQL("UPDATE diary_entries SET companionId = 1 WHERE author = 'ai'")
        }
    }
}

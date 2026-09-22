package com.cleo.cleos.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Schema changes must come with a Migration. Never fall back to destructive migration:
 * this database holds a diary, and "the app updated and my diary is empty" is the one
 * failure this app cannot have.
 */
@Database(
    entities = [ConversationEntity::class, MessageEntity::class, DiaryEntryEntity::class, TodoEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversations(): ConversationDao
    abstract fun messages(): MessageDao
    abstract fun diary(): DiaryDao
    abstract fun todos(): TodoDao
}

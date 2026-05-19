package com.viel.aplayer.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        BookEntity::class,
        BookFileEntity::class,
        BookProgressEntity::class,
        ChapterEntity::class,
        BookmarkEntity::class,
        LibraryRootEntity::class,
        ScanSessionEntity::class,
        PendingScanActionEntity::class
    ],
    // Schema v26 removes search history from Room; DataStore now owns that UI state.
    version = 26,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun chapterDao(): ChapterDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun libraryRootDao(): LibraryRootDao
    abstract fun scanSessionDao(): ScanSessionDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null
        
        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "aplayer_database"
                )
                .addMigrations(MIGRATION_25_26)
                .fallbackToDestructiveMigration(true)
                .build()
                INSTANCE = instance
                instance
            }
        }

        private val MIGRATION_25_26 = object : Migration(25, 26) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Search history is no longer part of the main schema; dropping it preserves all audiobook tables.
                db.execSQL("DROP TABLE IF EXISTS search_history")
            }
        }
    }
}

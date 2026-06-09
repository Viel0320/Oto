package com.viel.aplayer.data.db

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.viel.aplayer.abs.playback.AbsPendingProgressSyncDao
import com.viel.aplayer.abs.playback.AbsPendingProgressSyncEntity
import com.viel.aplayer.abs.playback.AbsPlaybackSessionDao
import com.viel.aplayer.abs.playback.AbsPlaybackSessionEntity
import com.viel.aplayer.abs.sync.AbsCatalogDao
import com.viel.aplayer.abs.sync.AbsItemMirrorDao
import com.viel.aplayer.abs.sync.AbsItemMirrorEntity
import com.viel.aplayer.abs.sync.AbsSyncStateDao
import com.viel.aplayer.abs.sync.AbsSyncStateEntity
import com.viel.aplayer.data.dao.BookDao
import com.viel.aplayer.data.dao.BookmarkDao
import com.viel.aplayer.data.dao.ChapterDao
import com.viel.aplayer.data.dao.DirectoryCacheDao
import com.viel.aplayer.data.dao.DirectoryChildCacheDao
import com.viel.aplayer.data.dao.LibraryRootDao
import com.viel.aplayer.data.dao.ScanSessionDao
import com.viel.aplayer.data.entity.BookEntity
import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.data.entity.BookProgressEntity
import com.viel.aplayer.data.entity.BookmarkEntity
import com.viel.aplayer.data.entity.ChapterEntity
import com.viel.aplayer.data.entity.DirectoryCacheEntity
import com.viel.aplayer.data.entity.DirectoryChildCacheEntity
import com.viel.aplayer.data.entity.LibraryRootEntity
import com.viel.aplayer.data.entity.ScanSessionEntity
import java.io.File

@Database(
    entities = [
        BookEntity::class,
        BookFileEntity::class,
        BookProgressEntity::class,
        ChapterEntity::class,
        BookmarkEntity::class,
        LibraryRootEntity::class,
        ScanSessionEntity::class,
        DirectoryCacheEntity::class,
        DirectoryChildCacheEntity::class,
        AbsSyncStateEntity::class,
        AbsItemMirrorEntity::class,
        AbsPlaybackSessionEntity::class,
        AbsPendingProgressSyncEntity::class
    ],
    // Database Schema Design (Adds WebDAV directory child snapshots for scanner-only listing cache reuse)
    // Version 37 introduces series column to the books table.
    // Upgrade database version to 39 to add high-frequency search index configurations on books table (readStatus, series, author, narrator).
    // Upgrade database version to 40 to remove obsolete local scan pending-action storage after conflicts became deterministic imports.
    // Upgrade database version to 41 to timestamp ABS playback sessions for online cache TTL fallback.
    version = 41,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun chapterDao(): ChapterDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun libraryRootDao(): LibraryRootDao
    abstract fun scanSessionDao(): ScanSessionDao
    // Directory Cache DAO (Expose query interfaces for scanning timestamps of local directories)
    abstract fun directoryCacheDao(): DirectoryCacheDao
    // Directory Children Cache DAO (Expose direct directory listing snapshots for WebDAV scan reuse)
    abstract fun directoryChildCacheDao(): DirectoryChildCacheDao
    abstract fun absSyncStateDao(): AbsSyncStateDao
    abstract fun absItemMirrorDao(): AbsItemMirrorDao
    abstract fun absCatalogDao(): AbsCatalogDao
    abstract fun absPlaybackSessionDao(): AbsPlaybackSessionDao
    abstract fun absPendingProgressSyncDao(): AbsPendingProgressSyncDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        // Schema Migration 36 to 37 (Adds series column to the books table)
        // Enforces table structural upgrade, preventing data loss by declaring a migration path.
        private val MIGRATION_36_37 = object : androidx.room.migration.Migration(36, 37) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN series TEXT NOT NULL DEFAULT ''")
            }
        }

        // Schema Migration 39 to 40 (Removes obsolete scan pending queue)
        // Drops pending_scan_actions and rebuilds scan_sessions without pendingActionCount while preserving completed scan summaries.
        private val MIGRATION_39_40 = object : androidx.room.migration.Migration(39, 40) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS scan_sessions_new (
                        id TEXT NOT NULL,
                        trigger TEXT NOT NULL,
                        status TEXT NOT NULL,
                        startedAt INTEGER NOT NULL,
                        completedAt INTEGER,
                        abandonedAt INTEGER,
                        discoveredBookCount INTEGER NOT NULL,
                        unavailableBookCount INTEGER NOT NULL,
                        partialBookCount INTEGER NOT NULL,
                        updatedBookCount INTEGER NOT NULL,
                        summaryJson TEXT NOT NULL,
                        PRIMARY KEY(id)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO scan_sessions_new (
                        id,
                        trigger,
                        status,
                        startedAt,
                        completedAt,
                        abandonedAt,
                        discoveredBookCount,
                        unavailableBookCount,
                        partialBookCount,
                        updatedBookCount,
                        summaryJson
                    )
                    SELECT
                        id,
                        trigger,
                        status,
                        startedAt,
                        completedAt,
                        abandonedAt,
                        discoveredBookCount,
                        unavailableBookCount,
                        partialBookCount,
                        updatedBookCount,
                        summaryJson
                    FROM scan_sessions
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE scan_sessions")
                db.execSQL("ALTER TABLE scan_sessions_new RENAME TO scan_sessions")
                db.execSQL("DROP TABLE IF EXISTS pending_scan_actions")
            }
        }

        // Schema Migration 40 to 41 (Adds ABS playback session opening timestamp)
        // Existing rows receive zero so the TTL policy treats them as stale and recreates remote runtime sessions safely.
        private val MIGRATION_40_41 = object : androidx.room.migration.Migration(40, 41) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE abs_playback_session ADD COLUMN openedAt INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "aplayer_database"
                )
                .addMigrations(MIGRATION_36_37, MIGRATION_39_40, MIGRATION_40_41)
                // Destructive Migration Strategy (Wipe databases and rebuild layout if schema versions mismatch)
                .fallbackToDestructiveMigration(true)
                .addCallback(object : Callback() {
                    override fun onDestructiveMigration(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        super.onDestructiveMigration(db)
                        // Cache Eviction Coordinator (Purge cached cover art and thumbnails upon destructive migrations)
                        // Deletes sandboxed images to prevent orphan files when metadata indexes are completely wiped.
                        try {
                            val coversDir = File(context.applicationContext.cacheDir, "covers")
                            if (coversDir.exists() && coversDir.isDirectory) {
                                coversDir.listFiles()?.forEach { file ->
                                    if (file.isFile) {
                                        val deleted = file.delete()
                                        Log.d("AppDatabase", "Destructive migration: deleted orphan cover file ${file.name} (success=$deleted)")
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("AppDatabase", "Error clearing orphan cover cache during destructive migration", e)
                        }
                    }
                })
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

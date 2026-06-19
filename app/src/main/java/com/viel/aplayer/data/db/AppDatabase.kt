package com.viel.aplayer.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
import com.viel.aplayer.data.dao.DownloadMetadataDao
import com.viel.aplayer.data.dao.LibraryRootDao
import com.viel.aplayer.data.dao.ScanSessionDao
import com.viel.aplayer.data.entity.BookEntity
import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.data.entity.BookProgressEntity
import com.viel.aplayer.data.entity.BookmarkEntity
import com.viel.aplayer.data.entity.ChapterEntity
import com.viel.aplayer.data.entity.DirectoryCacheEntity
import com.viel.aplayer.data.entity.DirectoryChildCacheEntity
import com.viel.aplayer.data.entity.DownloadMetadataEntity
import com.viel.aplayer.data.entity.LibraryRootEntity
import com.viel.aplayer.data.entity.ScanSessionEntity

// Type Converter Registration: Register the newly created converters class on AppDatabase so Room maps enums properly.
@TypeConverters(AudiobookDatabaseConverters::class)
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
        AbsPendingProgressSyncEntity::class,
        DownloadMetadataEntity::class
    ],
    // Production Schema Baseline (Starts the supported Room migration history at version 41)
    // Schema versions before 41 are intentionally removed from source control, so future releases must add explicit forward-only migrations from this baseline instead of rebuilding user data.
    // Download Metadata Schema Version (Adds the book-level manual download aggregate table)
    // Version 43 keeps the existing user catalog intact while introducing the offline-cache metadata boundary.
    version = 43,
    exportSchema = true
)
// Restore AppDatabase Declaration: Restore the abstract class declaration to compile database schema correctly.
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
    abstract fun downloadMetadataDao(): DownloadMetadataDao

    companion object {
        const val VERSION = 43
        @Volatile
        private var INSTANCE: AppDatabase? = null

        // Drop Bookmark Foreign Keys Migration (Recreate bookmarks table without foreign keys to prevent data loss on file remapping)
        private val MIGRATION_41_42 = object : Migration(41, 42) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Recreate bookmarks table: SQLite does not support dropping foreign keys directly via ALTER TABLE, so a full table recreation is required.
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `bookmarks_new` (
                        `id` TEXT NOT NULL, 
                        `bookId` TEXT NOT NULL, 
                        `globalPositionMs` INTEGER NOT NULL, 
                        `bookFileId` TEXT, 
                        `fileOffsetMs` INTEGER NOT NULL, 
                        `fileFingerprint` TEXT, 
                        `anchorStatus` TEXT NOT NULL, 
                        `title` TEXT NOT NULL, 
                        `createdAt` INTEGER NOT NULL, 
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO `bookmarks_new` (`id`, `bookId`, `globalPositionMs`, `bookFileId`, `fileOffsetMs`, `fileFingerprint`, `anchorStatus`, `title`, `createdAt`)
                    SELECT `id`, `bookId`, `globalPositionMs`, `bookFileId`, `fileOffsetMs`, `fileFingerprint`, `anchorStatus`, `title`, `createdAt` FROM `bookmarks`
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE `bookmarks`")
                db.execSQL("ALTER TABLE `bookmarks_new` RENAME TO `bookmarks`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_bookmarks_bookId` ON `bookmarks` (`bookId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_bookmarks_bookFileId` ON `bookmarks` (`bookFileId`)")
            }
        }

        // Download Metadata Migration (Creates the app-level aggregate table for manual download status)
        // This migration only adds new storage and indices, so books, files, chapters, progress, bookmarks, roots, and ABS mirror rows remain untouched.
        internal val MIGRATION_42_43 = object : Migration(42, 43) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `download_metadata` (
                        `bookId` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `totalFiles` INTEGER NOT NULL,
                        `completedFiles` INTEGER NOT NULL,
                        `totalBytes` INTEGER NOT NULL,
                        `downloadedBytes` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`bookId`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_download_metadata_status` ON `download_metadata` (`status`)")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                // Non-Destructive Database Builder (Reject unsupported schema gaps instead of wiping persisted user data)
                // Room will fail fast when a future schema change lacks an explicit migration, protecting progress, bookmarks, roots, and ABS mirror rows from silent destructive rebuilds.
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "aplayer_database"
                )
                    .addMigrations(MIGRATION_41_42, MIGRATION_42_43)
                    .build()
                INSTANCE = instance
                instance
            }
        }

        // Title: Close Database Instance (Close the active database instance and reset its singleton reference to allow data import)
        // Explicitly terminates Room operations and releases file locks so the database file can be replaced cleanly.
        // Catches and swallows any close exceptions (e.g. from missing files in stale test environments) to ensure singleton reset always completes.
        fun closeInstance() {
            synchronized(this) {
                INSTANCE?.let {
                    try {
                        if (it.isOpen) {
                            it.close()
                        }
                    } catch (_: Exception) {
                        // Title: Ignore Close Exception (Swallow database close exceptions when instance is already invalid)
                        // Prevents errors from stale test contexts or missing databases from aborting the close workflow.
                    } finally {
                        INSTANCE = null
                    }
                }
            }
        }
    }
}

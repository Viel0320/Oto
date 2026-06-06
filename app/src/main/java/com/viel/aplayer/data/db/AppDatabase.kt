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
import com.viel.aplayer.data.entity.PendingScanActionEntity
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
        PendingScanActionEntity::class,
        DirectoryCacheEntity::class,
        DirectoryChildCacheEntity::class,
        AbsSyncStateEntity::class,
        AbsItemMirrorEntity::class,
        AbsPlaybackSessionEntity::class,
        AbsPendingProgressSyncEntity::class
    ],
    // Database Schema Design (Adds WebDAV directory child snapshots for scanner-only listing cache reuse)
    // Version 37 introduces series column to the books table.
    // Upgrade database version to 38 due to deprecation of backgroundColorArgb field. Room will automatically wipe DB for local developer devices.
    version = 38,
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

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "aplayer_database"
                )
                .addMigrations(MIGRATION_36_37)
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

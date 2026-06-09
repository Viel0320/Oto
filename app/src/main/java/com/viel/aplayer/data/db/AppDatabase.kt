package com.viel.aplayer.data.db

import android.content.Context
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
    // Production Schema Baseline (Starts the supported Room migration history at version 41)
    // Schema versions before 41 are intentionally removed from source control, so future releases must add explicit forward-only migrations from this baseline instead of rebuilding user data.
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

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                // Non-Destructive Database Builder (Reject unsupported schema gaps instead of wiping persisted user data)
                // Room will fail fast when a future schema change lacks an explicit migration, protecting progress, bookmarks, roots, and ABS mirror rows from silent destructive rebuilds.
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "aplayer_database"
                )
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

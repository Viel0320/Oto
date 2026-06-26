package com.viel.oto.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.viel.oto.data.abs.playback.AbsPendingProgressSyncDao
import com.viel.oto.data.abs.playback.AbsPendingProgressSyncEntity
import com.viel.oto.data.abs.playback.AbsPlaybackSessionDao
import com.viel.oto.data.abs.playback.AbsPlaybackSessionEntity
import com.viel.oto.data.abs.sync.AbsCatalogDao
import com.viel.oto.data.abs.sync.AbsItemMirrorDao
import com.viel.oto.data.abs.sync.AbsItemMirrorEntity
import com.viel.oto.data.abs.sync.AbsSyncStateDao
import com.viel.oto.data.abs.sync.AbsSyncStateEntity
import com.viel.oto.data.dao.BookDao
import com.viel.oto.data.dao.BookmarkDao
import com.viel.oto.data.dao.ChapterDao
import com.viel.oto.data.dao.DirectoryCacheDao
import com.viel.oto.data.dao.DirectoryChildCacheDao
import com.viel.oto.data.dao.DownloadMetadataDao
import com.viel.oto.data.dao.LibraryRootDao
import com.viel.oto.data.dao.ScanSessionDao
import com.viel.oto.data.entity.BookEntity
import com.viel.oto.data.entity.BookFileEntity
import com.viel.oto.data.entity.BookProgressEntity
import com.viel.oto.data.entity.BookmarkEntity
import com.viel.oto.data.entity.ChapterEntity
import com.viel.oto.data.entity.DirectoryCacheEntity
import com.viel.oto.data.entity.DirectoryChildCacheEntity
import com.viel.oto.data.entity.DownloadMetadataEntity
import com.viel.oto.data.entity.LibraryRootEntity
import com.viel.oto.data.entity.ScanSessionEntity

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
    version = 43,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun chapterDao(): ChapterDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun libraryRootDao(): LibraryRootDao
    abstract fun scanSessionDao(): ScanSessionDao
    abstract fun directoryCacheDao(): DirectoryCacheDao
    abstract fun directoryChildCacheDao(): DirectoryChildCacheDao
    abstract fun absSyncStateDao(): AbsSyncStateDao
    abstract fun absItemMirrorDao(): AbsItemMirrorDao
    abstract fun absCatalogDao(): AbsCatalogDao
    abstract fun absPlaybackSessionDao(): AbsPlaybackSessionDao
    abstract fun absPendingProgressSyncDao(): AbsPendingProgressSyncDao
    abstract fun downloadMetadataDao(): DownloadMetadataDao

    companion object {
        const val VERSION = 43
        internal val MIGRATION_41_42 = object : Migration(41, 42) {
            override fun migrate(db: SupportSQLiteDatabase) {
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

        /**
         * Construct an AppDatabase bound to the supplied context without caching it globally.
         * Used by Koin module wiring so the database lifecycle is owned by the di container.
         */
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "oto_database"
            )
                .addMigrations(MIGRATION_41_42, MIGRATION_42_43)
                .build()
    }
}

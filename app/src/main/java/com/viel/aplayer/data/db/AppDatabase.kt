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
import com.viel.aplayer.data.dao.LibraryRootDao
import com.viel.aplayer.data.dao.ScanSessionDao
import com.viel.aplayer.data.entity.BookEntity
import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.data.entity.BookProgressEntity
import com.viel.aplayer.data.entity.BookmarkEntity
import com.viel.aplayer.data.entity.ChapterEntity
import com.viel.aplayer.data.entity.DirectoryCacheEntity
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
        AbsSyncStateEntity::class,
        AbsItemMirrorEntity::class,
        AbsPlaybackSessionEntity::class,
        AbsPendingProgressSyncEntity::class
    ],
    // book_files 删除旧 uri 列后直接进入 VFS-only 结构，不保留历史迁移兼容链。
    version = 35,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun chapterDao(): ChapterDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun libraryRootDao(): LibraryRootDao
    abstract fun scanSessionDao(): ScanSessionDao
    // 对外暴露增量扫描目录缓存表的 DAO 查询接口
    abstract fun directoryCacheDao(): DirectoryCacheDao
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
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "aplayer_database"
                )
                // 旧数据库结构不再维护迁移链，结构不匹配时直接重建为当前 VFS 标准件表结构。
                .fallbackToDestructiveMigration(true)
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onDestructiveMigration(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        super.onDestructiveMigration(db)
                        // 详尽的中文注释：在 Room 数据发生破坏性结构迁移清库时，级联物理清空缓存中的 covers 封面及缩略图目录；
                        // 彻底防范旧有数据库记录被全量擦除重建后，存留在沙盒中的物理图片文件永久失去索引，形成永久无法回收的垃圾孤儿文件
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

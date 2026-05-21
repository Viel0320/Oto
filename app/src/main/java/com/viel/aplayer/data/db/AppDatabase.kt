package com.viel.aplayer.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.viel.aplayer.data.dao.BookDao
import com.viel.aplayer.data.dao.BookmarkDao
import com.viel.aplayer.data.dao.ChapterDao
import com.viel.aplayer.data.dao.LibraryRootDao
import com.viel.aplayer.data.dao.ScanSessionDao
import com.viel.aplayer.data.entity.BookEntity
import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.data.entity.BookProgressEntity
import com.viel.aplayer.data.entity.BookmarkEntity
import com.viel.aplayer.data.entity.ChapterEntity
import com.viel.aplayer.data.entity.LibraryRootEntity
import com.viel.aplayer.data.entity.PendingScanActionEntity
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
        PendingScanActionEntity::class
    ],
    // 为每一次改动添加详尽的中文注释：升级 Room 数据库版本到 27，以激活 Destructive Migration 重建机制应用新字段
    version = 27,
    exportSchema = true
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
        
        // 为每一次改动添加详尽的中文注释：声明从版本 26 升级至 27 的平滑迁移器。在级联外键架构大升级下提供迁移路径，防御线上崩溃风险 (H-16, H-17)
        private val MIGRATION_26_27 = object : androidx.room.migration.Migration(26, 27) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // 清理已迁移到 DataStore 的旧 search_history 表（H-19）
                database.execSQL("DROP TABLE IF EXISTS search_history")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "aplayer_database"
                )
                .addMigrations(MIGRATION_26_27)
                .fallbackToDestructiveMigration(true)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
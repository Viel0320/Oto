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
import com.viel.aplayer.data.dao.DirectoryCacheDao
import com.viel.aplayer.data.entity.BookEntity
import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.data.entity.BookProgressEntity
import com.viel.aplayer.data.entity.BookmarkEntity
import com.viel.aplayer.data.entity.ChapterEntity
import com.viel.aplayer.data.entity.LibraryRootEntity
import com.viel.aplayer.data.entity.PendingScanActionEntity
import com.viel.aplayer.data.entity.ScanSessionEntity
import com.viel.aplayer.data.entity.DirectoryCacheEntity

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
        DirectoryCacheEntity::class
    ],
    // 为每一次改动添加详尽的中文注释：升级 Room 数据库版本至 28，以引入增量扫描文件夹时间戳状态缓存表 (directory_cache)
    version = 28,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun chapterDao(): ChapterDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun libraryRootDao(): LibraryRootDao
    abstract fun scanSessionDao(): ScanSessionDao
    // 为每一次改动添加详尽的中文注释：对外暴露增量扫描目录缓存表的 DAO 查询接口
    abstract fun directoryCacheDao(): DirectoryCacheDao

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

        // 为每一次改动添加详尽的中文注释：声明从版本 27 升级至 28 的物理平滑迁移器。
        // 在 SQLite 引擎中物理创建增量目录扫描缓存表 `directory_cache` 并建立 CASCADE 级联外键级联，同时在 rootId 外键字段上建立索引，
        // 确保新表完美支持根目录删除级联清除规则，且检索效率达到最优，且绝不会导致存量用户数据发生清空丢失。
        private val MIGRATION_27_28 = object : androidx.room.migration.Migration(27, 28) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `directory_cache` (`directoryUri` TEXT NOT NULL, `lastModified` INTEGER NOT NULL, `rootId` TEXT NOT NULL, PRIMARY KEY(`directoryUri`), FOREIGN KEY(`rootId`) REFERENCES `library_roots`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_directory_cache_rootId` ON `directory_cache` (`rootId`)"
                )
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "aplayer_database"
                )
                .addMigrations(MIGRATION_26_27, MIGRATION_27_28)
                .fallbackToDestructiveMigration(true)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
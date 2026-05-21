package com.viel.aplayer.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.viel.aplayer.data.entity.DirectoryCacheEntity

/**
 * 为每一次改动添加详尽的中文注释：
 * 专用于增量目录扫描修改时间戳缓存表（directory_cache）的数据访问对象接口 (DAO)。
 * 承载了增量扫描秒级拦截、单目录状态覆盖缓存以及清空操作。
 */
@Dao
interface DirectoryCacheDao {

    /**
     * 根据文件夹 URI 检索其已缓存的 lastModified 修改时间戳状态。
     * 用于在扫描开始前比对是否可秒级跳过物理文件分析。
     */
    @Query("SELECT * FROM directory_cache WHERE directoryUri = :uri")
    suspend fun getByUri(uri: String): DirectoryCacheEntity?

    /**
     * 写入或更新某个文件夹的 lastModified 缓存状态。
     * 当文件夹物理信息发生变动或初次导入完毕时，将最新状态持久化落库。
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(cache: DirectoryCacheEntity)

    /**
     * 根据文件夹 URI 物理删除对应的缓存记录。
     */
    @Query("DELETE FROM directory_cache WHERE directoryUri = :uri")
    suspend fun deleteByUri(uri: String)

    /**
     * 根据媒体库根 ID 物理清空对应的所有文件夹缓存。
     * 注：在外键配置了 ON DELETE CASCADE 的级联关系下，
     * 级联删除库根记录时 SQLite 会自动触发底层删除，本方法作为数据手动操作的后备 API。
     */
    @Query("DELETE FROM directory_cache WHERE rootId = :rootId")
    suspend fun deleteByRootId(rootId: String)
}

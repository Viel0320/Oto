package com.viel.aplayer.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 为每一次改动添加详尽的中文注释：
 * 用于缓存各个文件夹（目录）物理 lastModified 修改时间戳的 Room 数据表实体。
 * 本表将 LibraryRootEntity 声明为外键，并配置了级联 CASCADE 删除规则。
 * 当用户在应用中物理删除某个媒体库根目录时，Room 数据库将在 SQLite 引擎层，
 * 自动且安全地将该媒体库下所有已缓存的文件夹状态和 lastModified 记录一并清除。
 * 这保证了随着文件夹的移除，绝不会在系统数据库中留下任何无用缓存的冗余垃圾记录，
 * 实现了极高内聚的自愈化生命周期关联。
 */
@Entity(
    tableName = "directory_cache",
    foreignKeys = [
        ForeignKey(
            entity = LibraryRootEntity::class,
            parentColumns = ["id"],
            childColumns = ["rootId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("rootId")]
)
data class DirectoryCacheEntity(
    /**
     * 该文件夹在 SAF 体系下的唯一 URI 标识，用作主键
     */
    @PrimaryKey
    val directoryUri: String,

    /**
     * 对应物理文件夹的最新的修改时间戳（由 directory.lastModified() 读出）
     */
    val lastModified: Long,

    /**
     * 该文件夹所属的媒体库根授权目录 ID，用于外键级联 CASCADE 级联清理
     */
    val rootId: String
)

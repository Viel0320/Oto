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
     * 为远程 VFS 预留的目录 etag；SAF 当前没有可靠 etag，保持 null，不影响现有 lastModified 缓存判断。
     */
    val etag: String? = null,

    /**
     * childSignature 表示目录子项快照签名；WebDAV 无 etag 时可用它做增量判断，SAF 第一阶段暂不计算。
     */
    val childSignature: String? = null,

    /**
     * lastCheckedAt 记录最近一次目录缓存校验时间，后续远程源可用来控制探测频率，SAF 默认不改变现有行为。
     */
    val lastCheckedAt: Long = 0L,

    /**
     * availabilityStatus 让目录级不可用状态独立于根目录状态，远程目录临时失败时不会误伤整本书库。
     */
    val availabilityStatus: String = "UNKNOWN",

    /**
     * 该文件夹所属的媒体库根授权目录 ID，用于外键级联 CASCADE 级联清理
     */
    val rootId: String
)

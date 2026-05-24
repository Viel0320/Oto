package com.viel.aplayer.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.viel.aplayer.data.db.AudiobookSchema

/**
 * 媒体库授权目录实体。
 */
@Entity(tableName = "library_roots")
data class LibraryRootEntity(
    @PrimaryKey
    val id: String,
    // 为每一次改动添加详尽的中文注释：sourceType 是本地 SAF、WebDAV 等来源标准件的分发键，业务层不再依赖旧库根字段。
    val sourceType: String = AudiobookSchema.LibrarySourceType.SAF,
    // 为每一次改动添加详尽的中文注释：sourceUri 是跨来源的根地址；SAF 写入 tree URI，WebDAV 后续写入规范化服务器地址。
    val sourceUri: String,
    // basePath 描述来源内部的库根路径；SAF 当前为空，WebDAV 后续可存 /audiobooks 这类远程根目录。
    val basePath: String = "",
    // credentialId 只保存凭据引用，不保存密码本体，为后续 Keystore/加密存储接入留出安全边界。
    val credentialId: String? = null,
    // availabilityStatus 由统一可用性检测标准件写入，避免把 SAF 授权状态和远程网络状态塞进同一个 status 字段。
    val availabilityStatus: String = AudiobookSchema.AvailabilityStatus.UNKNOWN,
    // lastAvailabilityCheckedAt 记录最近一次来源可用性探测时间，便于 UI 和后续后台检测区分“未知”和“刚失败”。
    val lastAvailabilityCheckedAt: Long = 0L,
    // lastAvailabilityErrorCode 保存可解释的失败码；SAF v1 常见为 REVOKED/NOT_FOUND，WebDAV 后续扩展 AUTH_FAILED/TIMEOUT。
    val lastAvailabilityErrorCode: String? = null,
    val displayName: String,
    val grantedAt: Long = System.currentTimeMillis(),
    val lastScannedAt: Long = 0L,
    val status: String = "ACTIVE" // ACTIVE / REVOKED / ERROR
)

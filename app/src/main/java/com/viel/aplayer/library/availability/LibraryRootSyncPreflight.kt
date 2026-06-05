package com.viel.aplayer.library.availability

import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.LibraryRootEntity
import com.viel.aplayer.library.vfs.sourceProvider.LibrarySourceKind

/**
 * Refreshed Root Availability Snapshot (Carries persisted root state after a sync preflight check)
 * Stores both the updated LibraryRootEntity and the low-level AvailabilityResult so callers can decide whether a sync may start and explain blocked roots to the user.
 */
data class LibraryRootAvailabilityUpdate(
    val root: LibraryRootEntity,
    val availability: AvailabilityResult
)

/**
 * Sync Eligibility Decision (Converts refreshed reachability state into a start-or-block decision)
 * Requires both the public root status and the protocol-specific availability probe to be healthy before any scan or ABS catalog sync proceeds.
 */
internal val LibraryRootAvailabilityUpdate.isSyncAvailable: Boolean
    get() = root.status == AudiobookSchema.LibraryRootStatus.ACTIVE && availability.isAvailable

/**
 * Directory Sync Root Filter (Limits file-tree scans to providers that expose enumerable directories)
 * Excludes ABS because ABS catalog mirroring is handled through its REST synchronization path rather than SourceInventoryScanner directory traversal.
 */
internal fun LibraryRootEntity.isDirectorySyncRoot(): Boolean =
    when (LibrarySourceKind.from(sourceType)) {
        LibrarySourceKind.SAF,
        LibrarySourceKind.WEBDAV -> true
        LibrarySourceKind.ABS,
        null -> false
    }

/**
 * Unavailable Root Toast Builder (Creates a user-facing skip message for one blocked root)
 * Translates storage and network availability codes into short Chinese reasons while keeping sensitive endpoint details out of toast text.
 */
internal fun buildRootUnavailableSyncMessage(update: LibraryRootAvailabilityUpdate): String {
    val rootName = update.root.displayName.ifBlank { update.root.sourceUri }
    val reason = when (update.availability.status) {
        AudiobookSchema.AvailabilityStatus.REVOKED -> "授权已失效"
        AudiobookSchema.AvailabilityStatus.AUTH_FAILED -> "认证失败"
        AudiobookSchema.AvailabilityStatus.NETWORK_UNAVAILABLE -> "网络不可用"
        AudiobookSchema.AvailabilityStatus.TIMEOUT -> "连接超时"
        AudiobookSchema.AvailabilityStatus.NOT_FOUND -> "路径或远程书库不存在"
        AudiobookSchema.AvailabilityStatus.PERMISSION_DENIED -> "权限不足"
        AudiobookSchema.AvailabilityStatus.SERVER_ERROR -> "服务器错误"
        AudiobookSchema.AvailabilityStatus.UNSUPPORTED -> "来源不受支持"
        else -> update.availability.errorCode ?: update.availability.status
    }
    return "库根不可用，已跳过同步：$rootName（$reason）"
}

/**
 * Unavailable Roots Summary Builder (Creates a compact user-facing skip message for global scans)
 * Collapses multiple blocked roots into a bounded name list so repeated background checks do not produce oversized toast text.
 */
internal fun buildUnavailableRootsSyncMessage(updates: List<LibraryRootAvailabilityUpdate>): String {
    if (updates.isEmpty()) return "没有可同步的库根"
    if (updates.size == 1) return buildRootUnavailableSyncMessage(updates.first())
    val names = updates
        .take(3)
        .joinToString("、") { update -> update.root.displayName.ifBlank { update.root.sourceUri } }
    val suffix = if (updates.size > 3) "等 ${updates.size} 个库根" else names
    return "部分库根不可用，已跳过同步：$suffix"
}

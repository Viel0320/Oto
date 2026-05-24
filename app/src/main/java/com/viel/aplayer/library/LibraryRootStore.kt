package com.viel.aplayer.library

import android.content.Context
import android.net.Uri
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.viel.aplayer.data.db.AppDatabase
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.LibraryRootEntity
import com.viel.aplayer.library.availability.AvailabilityChecker

/**
 * 负责管理媒体库授权目录的持久化存储。
 */
class LibraryRootStore(private val context: Context) {
    private val rootDao = AppDatabase.getInstance(context).libraryRootDao()
    // 统一根目录可用性探测入口；SAF 阶段仍复刻旧授权检查，WebDAV 后续复用同一状态模型。
    private val availabilityChecker = AvailabilityChecker(context.applicationContext)

    /**
     * 添加新的授权目录。
     */
    suspend fun addRoot(uri: Uri, displayName: String): LibraryRootEntity = withContext(Dispatchers.IO) {
        val normalizedUri = uri.normalizeScheme().toString()
        rootDao.getAllRootsOnce()
            .firstOrNull { existing -> existing.isSameRoot(normalizedUri) }
            ?.let { existing ->
                // Re-selecting a stored root refreshes its grant state instead of inserting a duplicate row.
                rootDao.updateRootGrantState(
                    id = existing.id,
                    displayName = displayName,
                    grantedAt = System.currentTimeMillis(),
                    status = AudiobookSchema.LibraryRootStatus.ACTIVE
                )
                return@withContext existing.copy(
                    displayName = displayName,
                    grantedAt = System.currentTimeMillis(),
                    status = AudiobookSchema.LibraryRootStatus.ACTIVE
                )
            }
        val root = LibraryRootEntity(
            id = UUID.randomUUID().toString(),
            // 为每一次改动添加详尽的中文注释：本地 SAF 库根也写入通用 sourceUri，后续 WebDAV 复用同一个来源地址字段。
            sourceUri = normalizedUri,
            displayName = displayName,
            grantedAt = System.currentTimeMillis(),
            status = AudiobookSchema.LibraryRootStatus.ACTIVE
        )
        rootDao.insertRoot(root)
        root
    }

    suspend fun refreshPermissionStatuses() = withContext(Dispatchers.IO) {
        // Startup and settings entry both reconcile persisted SAF grants with stored root status.
        rootDao.getAllRootsOnce().forEach { root ->
            val availability = availabilityChecker.checkRoot(root)
            val status = if (availability.isAvailable) {
                AudiobookSchema.LibraryRootStatus.ACTIVE
            } else {
                AudiobookSchema.LibraryRootStatus.REVOKED
            }
            if (root.status != status) {
                rootDao.updateRootStatus(root.id, status)
            }
            rootDao.updateRootAvailability(
                id = root.id,
                availabilityStatus = availability.status,
                checkedAt = availability.checkedAt,
                errorCode = availability.errorCode
            )
        }
    }

    private fun LibraryRootEntity.isSameRoot(candidateTreeUri: String): Boolean =
        // 为每一次改动添加详尽的中文注释：重复库根检测改用 sourceUri，旧库根字段已从数据库模型中移除。
        sourceUri == candidateTreeUri || treeDocumentId(sourceUri) == treeDocumentId(candidateTreeUri)

    private fun treeDocumentId(sourceUri: String): String =
        Uri.decode(sourceUri).substringAfter("/tree/", missingDelimiterValue = sourceUri)
}

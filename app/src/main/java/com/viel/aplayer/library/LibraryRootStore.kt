package com.viel.aplayer.library

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import com.viel.aplayer.data.db.AppDatabase
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.LibraryRootEntity

/**
 * 负责管理媒体库授权目录的持久化存储。
 */
class LibraryRootStore(private val context: Context) {
    private val rootDao = AppDatabase.getInstance(context).libraryRootDao()
    private val prefs = context.getSharedPreferences("library_prefs", Context.MODE_PRIVATE)

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
            treeUri = normalizedUri,
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
            val status = if (hasReadAccess(root.treeUri)) {
                AudiobookSchema.LibraryRootStatus.ACTIVE
            } else {
                AudiobookSchema.LibraryRootStatus.REVOKED
            }
            if (root.status != status) {
                rootDao.updateRootStatus(root.id, status)
            }
        }
    }

    /**
     * 处理从旧版单目录 Prefs 到数据库的迁移。
     */
    suspend fun migrateLegacyRoot() {
        val legacyUriStr = prefs.getString("library_root_uri", null)
        if (legacyUriStr != null) {
            val roots = rootDao.getAllRoots().first()
            if (roots.none { it.isSameRoot(legacyUriStr) }) {
                addRoot(Uri.parse(legacyUriStr), "Primary Library")
            }
            // 迁移后清除旧标记，防止重复
            prefs.edit().remove("library_root_uri").apply()
        }
    }

    private fun LibraryRootEntity.isSameRoot(candidateTreeUri: String): Boolean =
        // URI string catches exact duplicates; documentId catches equivalent normalized SAF tree URIs.
        treeUri == candidateTreeUri ||
            treeDocumentId(treeUri) == treeDocumentId(candidateTreeUri)

    private fun hasReadAccess(treeUri: String): Boolean {
        val uri = Uri.parse(treeUri)
        val hasPersistedReadGrant = context.contentResolver.persistedUriPermissions.any { permission ->
            permission.isReadPermission && permission.uri.normalizeScheme().toString() == uri.normalizeScheme().toString()
        }
        if (!hasPersistedReadGrant) return false
        return DocumentFile.fromTreeUri(context, uri)?.exists() == true
    }

    private fun treeDocumentId(treeUri: String): String =
        Uri.decode(treeUri).substringAfter("/tree/", missingDelimiterValue = treeUri)
}
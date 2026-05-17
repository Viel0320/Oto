package com.viel.aplayer.library

import android.content.Context
import android.net.Uri
import com.viel.aplayer.data.AppDatabase
import com.viel.aplayer.data.LibraryRootEntity
import kotlinx.coroutines.flow.first
import java.util.UUID

/**
 * 负责管理媒体库授权目录的持久化存储。
 */
class LibraryRootStore(private val context: Context) {
    private val rootDao = AppDatabase.getInstance(context).libraryRootDao()
    private val prefs = context.getSharedPreferences("library_prefs", Context.MODE_PRIVATE)

    /**
     * 添加新的授权目录。
     */
    suspend fun addRoot(uri: Uri, displayName: String) {
        val root = LibraryRootEntity(
            id = UUID.randomUUID().toString(),
            treeUri = uri.toString(),
            displayName = displayName,
            grantedAt = System.currentTimeMillis(),
            status = "ACTIVE"
        )
        rootDao.insertRoot(root)
    }

    /**
     * 处理从旧版单目录 Prefs 到数据库的迁移。
     */
    suspend fun migrateLegacyRoot() {
        val legacyUriStr = prefs.getString("library_root_uri", null)
        if (legacyUriStr != null) {
            val roots = rootDao.getAllRoots().first()
            if (roots.none { it.treeUri == legacyUriStr }) {
                addRoot(Uri.parse(legacyUriStr), "Primary Library")
            }
            // 迁移后清除旧标记，防止重复
            prefs.edit().remove("library_root_uri").apply()
        }
    }
}

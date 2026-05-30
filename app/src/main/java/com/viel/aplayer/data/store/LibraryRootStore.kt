package com.viel.aplayer.library

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import com.viel.aplayer.data.db.AppDatabase
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.LibraryRootEntity
import com.viel.aplayer.library.availability.AvailabilityChecker
import com.viel.aplayer.library.vfs.sourceProvider.LibrarySourceKind
import com.viel.aplayer.library.vfs.sourceProvider.webdav.WebDavCredentialStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * 负责管理媒体库授权目录的持久化存储。
 */
class LibraryRootStore(private val context: Context) {
    private val rootDao = AppDatabase.getInstance(context).libraryRootDao()
    // 统一根目录可用性探测入口；SAF 阶段仍复刻旧授权检查，WebDAV 后续复用同一状态模型。
    private val availabilityChecker = AvailabilityChecker(context.applicationContext)
    // WebDAV 凭据由根目录仓库统一写入/更新，Room 只保存 credentialId 引用。
    private val webDavCredentialStore = WebDavCredentialStore(context.applicationContext)

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
            // 本地 SAF 库根也写入通用 sourceUri，后续 WebDAV 复用同一个来源地址字段。
            sourceUri = normalizedUri,
            displayName = displayName,
            grantedAt = System.currentTimeMillis(),
            status = AudiobookSchema.LibraryRootStatus.ACTIVE
        )
        rootDao.insertRoot(root)
        root
    }

    suspend fun addWebDavRoot(
        url: String,
        username: String,
        password: String,
        displayName: String,
        basePath: String = ""
    ): LibraryRootEntity = withContext(Dispatchers.IO) {
        val parsed = url.trim().toUri()
        val normalizedEndpoint = normalizeWebDavEndpoint(parsed)
        val normalizedBasePath = normalizeWebDavBasePath(basePath.ifBlank { parsed.path.orEmpty() })
        val resolvedDisplayName = displayName.ifBlank {
            // 未填写显示名时用主机与库内路径兜底，避免 UI 出现空白媒体库名称。
            buildString {
                append(parsed.host ?: normalizedEndpoint)
                if (normalizedBasePath.isNotBlank()) append(normalizedBasePath)
            }
        }
        val now = System.currentTimeMillis()
        rootDao.getAllRootsOnce()
            .firstOrNull { existing -> existing.isSameWebDavRoot(normalizedEndpoint, normalizedBasePath) }
            ?.let { existing ->
                val credential = webDavCredentialStore.save(
                    username = username,
                    password = password,
                    credentialId = existing.credentialId ?: UUID.randomUUID().toString()
                )
                val updated = existing.copy(
                    displayName = resolvedDisplayName,
                    credentialId = credential.id,
                    grantedAt = now,
                    status = AudiobookSchema.LibraryRootStatus.ACTIVE,
                    availabilityStatus = AudiobookSchema.AvailabilityStatus.UNKNOWN,
                    lastAvailabilityCheckedAt = 0L,
                    lastAvailabilityErrorCode = null
                )
                // 重复添加同一 WebDAV 根时更新凭据与显示名，不插入第二条 root。
                rootDao.insertRoot(updated)
                return@withContext updated
            }
        val credential = webDavCredentialStore.save(username = username, password = password)
        val root = LibraryRootEntity(
            id = UUID.randomUUID().toString(),
            sourceType = AudiobookSchema.LibrarySourceType.WEBDAV,
            sourceUri = normalizedEndpoint,
            basePath = normalizedBasePath,
            credentialId = credential.id,
            displayName = resolvedDisplayName,
            grantedAt = now,
            status = AudiobookSchema.LibraryRootStatus.ACTIVE
        )
        // WebDAV root 入库后立即可被现有 SourceInventoryScanner 作为标准来源扫描。
        rootDao.insertRoot(root)
        root
    }

    suspend fun refreshPermissionStatuses() = withContext(Dispatchers.IO) {
        // Startup and settings entry both reconcile persisted SAF grants with stored root status.
        rootDao.getAllRootsOnce().forEach { root ->
            val availability = availabilityChecker.checkRoot(root)
            val status = if (availability.isAvailable) {
                AudiobookSchema.LibraryRootStatus.ACTIVE
            } else if (LibrarySourceKind.from(root.sourceType) == LibrarySourceKind.SAF) {
                AudiobookSchema.LibraryRootStatus.REVOKED
            } else {
                // 远程来源失败不是 SAF 授权撤销，保留为 ERROR 便于后续重试和 UI 区分认证/网络问题。
                AudiobookSchema.LibraryRootStatus.ERROR
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
        // 重复库根检测改用 sourceUri，旧库根字段已从数据库模型中移除。
        sourceType == AudiobookSchema.LibrarySourceType.SAF &&
            (sourceUri == candidateTreeUri || treeDocumentId(sourceUri) == treeDocumentId(candidateTreeUri))

    private fun LibraryRootEntity.isSameWebDavRoot(candidateEndpoint: String, candidateBasePath: String): Boolean =
        // WebDAV 去重以来源类型、规范化端点和库内根路径共同判定，支持同服务器多个书库路径。
        sourceType == AudiobookSchema.LibrarySourceType.WEBDAV &&
            sourceUri == candidateEndpoint &&
            normalizeWebDavBasePath(basePath) == candidateBasePath

    private fun normalizeWebDavEndpoint(parsed: Uri): String {
        val scheme = parsed.scheme?.lowercase()
            ?: throw IllegalArgumentException("WebDAV URL 缺少协议")
        val authority = parsed.encodedAuthority
            ?: throw IllegalArgumentException("WebDAV URL 缺少主机")
        require(scheme == "http" || scheme == "https") { "WebDAV URL 仅支持 http/https" }
        // sourceUri 只保存协议与主机端点，库内路径统一放入 basePath。
        return "$scheme://$authority"
    }

    private fun normalizeWebDavBasePath(path: String): String =
        // basePath 统一成以 / 开头、无结尾 / 的远程库内路径，根目录用空字符串表示。
        Uri.decode(path)
            .replace('\\', '/')
            .trim()
            .trim('/')
            .takeIf { it.isNotBlank() }
            ?.let { "/$it" }
            .orEmpty()

    private fun treeDocumentId(sourceUri: String): String =
        Uri.decode(sourceUri).substringAfter("/tree/", missingDelimiterValue = sourceUri)
}

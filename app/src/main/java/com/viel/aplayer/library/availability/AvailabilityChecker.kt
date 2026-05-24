package com.viel.aplayer.library.availability

import android.content.Context
import androidx.core.net.toUri
import com.viel.aplayer.data.db.AppDatabase
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.data.entity.LibraryRootEntity
import com.viel.aplayer.library.source.LibrarySourceKind
import com.viel.aplayer.library.source.LibrarySourceProviderFactory
import com.viel.aplayer.library.vfs.VfsPath
import com.viel.aplayer.library.vfs.VirtualFileSystem

// 可用性状态使用独立模型承载，避免调用层继续把 SAF 授权、远程认证和网络失败混成 Boolean。
data class AvailabilityResult(
    val status: String,
    val checkedAt: Long = System.currentTimeMillis(),
    val errorCode: String? = null,
    val message: String? = null
) {
    val isAvailable: Boolean get() = status == AudiobookSchema.AvailabilityStatus.AVAILABLE
}

// AvailabilityChecker 是远程连接可用检测标准件入口；第一阶段先复刻 SAF/file 的旧 Boolean 行为。
class AvailabilityChecker(private val context: Context) {
    private val database = AppDatabase.getInstance(context.applicationContext)
    private val libraryRootDao = database.libraryRootDao()
    private val vfs = VirtualFileSystem(LibrarySourceProviderFactory(context.applicationContext))

    suspend fun checkRoot(root: LibraryRootEntity): AvailabilityResult =
        when (LibrarySourceKind.from(root.sourceType)) {
            LibrarySourceKind.SAF -> checkSafRoot(root)
            LibrarySourceKind.WEBDAV -> AvailabilityResult(
                status = AudiobookSchema.AvailabilityStatus.UNSUPPORTED,
                errorCode = AudiobookSchema.AvailabilityStatus.UNSUPPORTED,
                message = "WebDAV provider is not implemented in phase 1"
            )
        }

    suspend fun checkBookFile(file: BookFileEntity): AvailabilityResult =
        runCatching {
            val root = libraryRootDao.getRootById(file.rootId)
                ?: return AvailabilityResult(
                    status = AudiobookSchema.AvailabilityStatus.NOT_FOUND,
                    errorCode = AudiobookSchema.AvailabilityStatus.NOT_FOUND
                )
            // 为每一次改动添加详尽的中文注释：已入库文件可用性检测通过 VFS sourcePath resolve/exists，不再回到 content Uri 或来源原生文件对象。
            val node = vfs.resolve(root, VfsPath(file.sourcePath))
            if (node != null && vfs.exists(node)) {
                AvailabilityResult(status = AudiobookSchema.AvailabilityStatus.AVAILABLE)
            } else {
                AvailabilityResult(
                    status = AudiobookSchema.AvailabilityStatus.NOT_FOUND,
                    errorCode = AudiobookSchema.AvailabilityStatus.NOT_FOUND
                )
            }
        }.getOrElse { throwable ->
            AvailabilityResult(
                status = AudiobookSchema.AvailabilityStatus.UNKNOWN,
                errorCode = throwable::class.java.simpleName,
                message = throwable.localizedMessage ?: throwable.message
            )
        }

    suspend fun checkBookFiles(files: List<BookFileEntity>): Map<String, AvailabilityResult> {
        // 为每一次改动添加详尽的中文注释：多文件书籍按 rootId/父目录批量检测，同一目录只枚举一次，避免几十个分轨重复 resolve SAF tree。
        if (files.isEmpty()) return emptyMap()
        val results = linkedMapOf<String, AvailabilityResult>()
        files.groupBy { it.rootId }.forEach { (rootId, rootFiles) ->
            val root = libraryRootDao.getRootById(rootId)
            if (root == null) {
                rootFiles.forEach { file -> results[file.id] = notFoundResult() }
            } else {
                when (LibrarySourceKind.from(root.sourceType)) {
                    LibrarySourceKind.SAF -> checkSafBookFiles(root, rootFiles, results)
                    LibrarySourceKind.WEBDAV -> rootFiles.forEach { file -> results[file.id] = unsupportedWebDavResult() }
                }
            }
        }
        return results
    }

    private suspend fun checkSafBookFiles(
        root: LibraryRootEntity,
        files: List<BookFileEntity>,
        results: MutableMap<String, AvailabilityResult>
    ) {
        runCatching {
            files.groupBy { it.sourcePath.substringBeforeLast('/', missingDelimiterValue = "") }
                .forEach { (parentPath, siblings) ->
                    val directory = vfs.resolve(root, VfsPath(parentPath))
                    if (directory == null) {
                        siblings.forEach { file -> results[file.id] = notFoundResult() }
                        return@forEach
                    }
                    // 为每一次改动添加详尽的中文注释：已入库音频只需要确认同级目录当前仍包含相同 sourcePath 的子项，不再对每个文件单独逐级 findFile。
                    val existingChildPaths = vfs.listChildren(directory)
                        .asSequence()
                        .filterNot { it.metadata.isDirectory }
                        .map { it.metadata.sourcePath }
                        .toSet()
                    siblings.forEach { file ->
                        results[file.id] = if (existingChildPaths.contains(file.sourcePath)) {
                            AvailabilityResult(status = AudiobookSchema.AvailabilityStatus.AVAILABLE)
                        } else {
                            notFoundResult()
                        }
                    }
                }
        }.getOrElse { throwable ->
            files.forEach { file ->
                results[file.id] = AvailabilityResult(
                    status = AudiobookSchema.AvailabilityStatus.UNKNOWN,
                    errorCode = throwable::class.java.simpleName,
                    message = throwable.localizedMessage ?: throwable.message
                )
            }
        }
    }

    private suspend fun checkSafRoot(root: LibraryRootEntity): AvailabilityResult {
        val sourceUri = root.sourceUri.toUri()
        val hasPersistedReadGrant = context.contentResolver.persistedUriPermissions.any { permission ->
            permission.isReadPermission && permission.uri.normalizeScheme().toString() == sourceUri.normalizeScheme().toString()
        }
        if (!hasPersistedReadGrant) {
            return AvailabilityResult(
                status = AudiobookSchema.AvailabilityStatus.REVOKED,
                errorCode = AudiobookSchema.AvailabilityStatus.REVOKED
            )
        }
        val exists = vfs.root(root)?.let { vfs.exists(it) } == true
        return if (exists) {
            AvailabilityResult(status = AudiobookSchema.AvailabilityStatus.AVAILABLE)
        } else {
            AvailabilityResult(
                status = AudiobookSchema.AvailabilityStatus.NOT_FOUND,
                errorCode = AudiobookSchema.AvailabilityStatus.NOT_FOUND
            )
        }
    }

    private fun notFoundResult(): AvailabilityResult =
        AvailabilityResult(
            status = AudiobookSchema.AvailabilityStatus.NOT_FOUND,
            errorCode = AudiobookSchema.AvailabilityStatus.NOT_FOUND
        )

    private fun unsupportedWebDavResult(): AvailabilityResult =
        AvailabilityResult(
            status = AudiobookSchema.AvailabilityStatus.UNSUPPORTED,
            errorCode = AudiobookSchema.AvailabilityStatus.UNSUPPORTED,
            message = "WebDAV provider is not implemented in phase 1"
        )
}

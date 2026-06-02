package com.viel.aplayer.library.availability

import android.content.Context
import androidx.core.net.toUri
import com.viel.aplayer.abs.net.AbsApiError
import com.viel.aplayer.abs.vfs.AbsSourceProvider
import com.viel.aplayer.data.db.AppDatabase
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.data.entity.LibraryRootEntity
import com.viel.aplayer.library.vfs.VfsPath
import com.viel.aplayer.library.vfs.VirtualFileSystem
import com.viel.aplayer.library.vfs.sourceProvider.LibrarySourceKind
import com.viel.aplayer.library.vfs.sourceProvider.LibrarySourceProviderFactory
import com.viel.aplayer.library.vfs.sourceProvider.webdav.WebDavException

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
            // WebDAV 根可用性直接走 VFS/Provider，HTTP 认证和网络错误由 Provider 映射为统一状态。
            LibrarySourceKind.WEBDAV -> checkVfsRoot(root)
            // 阶段 1 先把 ABS 根的可用性探测入口接到 provider，占位实现统一返回 UNSUPPORTED。
            LibrarySourceKind.ABS -> checkVfsRoot(root)
            null -> AvailabilityResult(
                status = AudiobookSchema.AvailabilityStatus.UNSUPPORTED,
                errorCode = "UNSUPPORTED_SOURCE_TYPE"
            )
        }

    suspend fun checkBookFile(file: BookFileEntity): AvailabilityResult =
        runCatching {
            val root = libraryRootDao.getRootById(file.rootId)
                ?: return AvailabilityResult(
                    status = AudiobookSchema.AvailabilityStatus.NOT_FOUND,
                    errorCode = AudiobookSchema.AvailabilityStatus.NOT_FOUND
                )
            if (LibrarySourceKind.from(root.sourceType) == LibrarySourceKind.ABS) {
                val provider = LibrarySourceProviderFactory(context.applicationContext).providerFor(root) as AbsSourceProvider
                return@runCatching if (provider.checkReadable(root, file.sourcePath)) {
                    AvailabilityResult(status = AudiobookSchema.AvailabilityStatus.AVAILABLE)
                } else {
                    AvailabilityResult(
                        status = AudiobookSchema.AvailabilityStatus.NOT_FOUND,
                        errorCode = AudiobookSchema.AvailabilityStatus.NOT_FOUND
                    )
                }
            }
            // 已入库文件可用性检测通过 VFS sourcePath resolve/exists，不再回到 content Uri 或来源原生文件对象。
            val node = vfs.resolve(root, VfsPath(file.sourcePath))
            if (node != null && vfs.exists(node)) {
                AvailabilityResult(status = AudiobookSchema.AvailabilityStatus.AVAILABLE)
            } else {
                AvailabilityResult(
                    status = AudiobookSchema.AvailabilityStatus.NOT_FOUND,
                    errorCode = AudiobookSchema.AvailabilityStatus.NOT_FOUND
                )
            }
        }.getOrElse { throwable -> throwable.toAvailabilityResult() }

    suspend fun checkBookFiles(files: List<BookFileEntity>): Map<String, AvailabilityResult> {
        // 多文件书籍按 rootId/父目录批量检测，同一目录只枚举一次，避免几十个分轨重复 resolve SAF tree。
        if (files.isEmpty()) return emptyMap()
        val results = linkedMapOf<String, AvailabilityResult>()
        files.groupBy { it.rootId }.forEach { (rootId, rootFiles) ->
            val root = libraryRootDao.getRootById(rootId)
            if (root == null) {
                rootFiles.forEach { file -> results[file.id] = notFoundResult() }
            } else {
                when (LibrarySourceKind.from(root.sourceType)) {
                    // 批量文件可用性检测只依赖 VFS 同目录枚举，SAF/WebDAV 共享同一套性能优化路径。
                    LibrarySourceKind.SAF,
                    LibrarySourceKind.WEBDAV,
                    LibrarySourceKind.ABS -> checkAbsBookFiles(rootFiles, results)
                    null -> rootFiles.forEach { file ->
                        results[file.id] = AvailabilityResult(
                            status = AudiobookSchema.AvailabilityStatus.UNSUPPORTED,
                            errorCode = "UNSUPPORTED_SOURCE_TYPE"
                        )
                    }
                }
            }
        }
        return results
    }

    private suspend fun checkVfsBookFiles(
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
                    // 已入库音频只需要确认同级目录当前仍包含相同 sourcePath 的子项，不再对每个文件单独逐级 findFile。
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
            val failure = throwable.toAvailabilityResult()
            files.forEach { file -> results[file.id] = failure }
        }
    }

    private suspend fun checkAbsBookFiles(
        files: List<BookFileEntity>,
        results: MutableMap<String, AvailabilityResult>
    ) {
        // 详尽中文注释：ABS provider 本身不支持目录枚举，`listChildren()` 固定返回空列表，
        // 所以批量可用性检查必须逐文件回落到 `checkBookFile()`，走 ABS 专属的 HEAD/readable 语义。
        // 否则详情页会把整本 ABS 书错误标记成不可用。
        files.forEach { file ->
            results[file.id] = checkBookFile(file)
        }
    }

    private suspend fun checkVfsRoot(root: LibraryRootEntity): AvailabilityResult =
        runCatching {
            // 远程根目录检测执行一次 VFS root/exists，避免业务层直接发 HTTP 探测请求。
            val exists = vfs.root(root)?.let { vfs.exists(it) } == true
            if (exists) {
                AvailabilityResult(status = AudiobookSchema.AvailabilityStatus.AVAILABLE)
            } else {
                notFoundResult()
            }
        }.getOrElse { throwable -> throwable.toAvailabilityResult() }

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

    private fun Throwable.toAvailabilityResult(): AvailabilityResult {
        val webDavError = this as? WebDavException
        val absError = this as? AbsApiError
        val status = webDavError?.availabilityStatus
            ?: absError?.availabilityStatus
            ?: AudiobookSchema.AvailabilityStatus.UNKNOWN
        // 统一异常出口保留 Provider 映射出的远端失败码，未知异常才降级为 UNKNOWN。
        return AvailabilityResult(
            status = status,
            errorCode = webDavError?.availabilityStatus ?: absError?.code ?: this::class.java.simpleName,
            message = localizedMessage ?: message
        )
    }
}

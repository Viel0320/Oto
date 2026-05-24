package com.viel.aplayer.library.availability

import android.content.Context
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.data.entity.LibraryRootEntity
import com.viel.aplayer.library.source.LibrarySourceKind
import java.io.File

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
    fun checkRoot(root: LibraryRootEntity): AvailabilityResult =
        when (LibrarySourceKind.from(root.sourceType)) {
            LibrarySourceKind.SAF -> checkSafRoot(root)
            LibrarySourceKind.WEBDAV -> AvailabilityResult(
                status = AudiobookSchema.AvailabilityStatus.UNSUPPORTED,
                errorCode = AudiobookSchema.AvailabilityStatus.UNSUPPORTED,
                message = "WebDAV provider is not implemented in phase 1"
            )
        }

    fun checkBookFile(file: BookFileEntity): AvailabilityResult =
        checkUri(file.uri)

    fun checkUri(uriString: String): AvailabilityResult =
        runCatching {
            val uri = uriString.toUri()
            val available = when (uri.scheme) {
                "content" -> DocumentFile.fromSingleUri(context, uri)?.exists() == true
                "file" -> File(uri.path ?: "").exists()
                else -> false
            }
            if (available) {
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

    private fun checkSafRoot(root: LibraryRootEntity): AvailabilityResult {
        val treeUri = root.treeUri.ifBlank { root.sourceUri }.toUri()
        val hasPersistedReadGrant = context.contentResolver.persistedUriPermissions.any { permission ->
            permission.isReadPermission && permission.uri.normalizeScheme().toString() == treeUri.normalizeScheme().toString()
        }
        if (!hasPersistedReadGrant) {
            return AvailabilityResult(
                status = AudiobookSchema.AvailabilityStatus.REVOKED,
                errorCode = AudiobookSchema.AvailabilityStatus.REVOKED
            )
        }
        val exists = DocumentFile.fromTreeUri(context, treeUri)?.exists() == true
        return if (exists) {
            AvailabilityResult(status = AudiobookSchema.AvailabilityStatus.AVAILABLE)
        } else {
            AvailabilityResult(
                status = AudiobookSchema.AvailabilityStatus.NOT_FOUND,
                errorCode = AudiobookSchema.AvailabilityStatus.NOT_FOUND
            )
        }
    }
}
